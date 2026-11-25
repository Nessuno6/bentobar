package com.meridian11.books.bentobar.items

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import com.google.ambient.crossdevice.Participant
import com.google.ambient.crossdevice.ParticipantImpl
import com.google.ambient.crossdevice.connections.ConnectionReceiver
import com.google.ambient.crossdevice.connections.RemoteConnection
import com.google.android.gms.dtdi.Dtdi
import com.google.android.gms.dtdi.analytics.CorrelationData
import com.google.android.gms.dtdi.core.WakeUpRequest
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.items.DevicesRules.Device
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Device batteries' link: another of the user's devices running BentoBar (a phone and a tablet), joined through Google's
 * Cross device SDK. Google Play services find the device (the picker lists the user's own devices, signed in to the same
 * account), wake BentoBar on it with [ACTION_WAKE] and carry the messages, encrypted; BentoBar opens nothing of its own.
 *
 * While linked, each side sends its battery and the devices it shows ([DeviceLinkRules]) at once and every half minute, and
 * shows what the other sent. The link lasts while both processes do: once it drops, Device batteries offers to link
 * again (the picker, one tap), and the Device link tile links again to the last device by itself ([autolink]). Of the link
 * only that device's name is kept, in the no-backup directory (`Kept.own("link")`), so the tile can find it after
 * BentoBar restarts; no name is logged.
 */
object Link {
    /** What Google Play services start BentoBar with on the device picked (`ui/LinkActivity`). */
    const val ACTION_WAKE = "com.meridian11.books.bentobar.LINK"
    private const val CHANNEL = "bentobar-batteries"
    private const val TAG = "BentoBarLink"

    private val scope = MainScope()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var connection: RemoteConnection? = null
    /**
     * The link's other direction on the same device: opening and accepting only register this side for a channel (Google
     * Play services carry what one side opens to the other's accepted one), so each side holds both, and hears the other
     * whichever of them linked first, or both at once.
     */
    @Volatile private var back: RemoteConnection? = null
    /**
     * The user unlinked here: this side sends nothing and shows nothing of the other, but goes on listening, so a tap on
     * the other device (its card, "Link again") links both at once, as the picker's wake did. Hearing the other side
     * again ends it.
     */
    @Volatile private var unlinked = false
    /** The other side unlinked (it said [DeviceLinkRules.BYE]): nothing is sent to it until it speaks again. */
    @Volatile private var peerLeft = false
    /** The last link went quiet once and was registered again; quiet again, the device is looked for anew. */
    private var requiet = false
    /** When the link was last registered again after the other side closed it, to hold a loop of closes back. */
    private var relistenedAt = 0L
    /** Messages sent and heard, for the log. */
    private var sends = 0
    private var heard = 0
    /** When the link last came up, so that a link the other side never speaks on counts as quiet too. */
    private var linkedAt = 0L
    /** When [follow] last looked for the device last linked. */
    private var followedAt = 0L
    @Volatile private var status: DeviceLinkRules.Status? = null
    @Volatile private var statusAt = 0L
    /** The device last linked, as Google names it: kept after the link drops, for "Link to … again". */
    @Volatile var peer: String? = null; private set
    /** A link being opened (the other side is being woken). */
    @Volatile var connecting = false; private set

    @Volatile private var attempt = 0
    private var connectTimeout: Runnable? = null

    /** The device last linked to, picked here or accepted: what the link opens again after Quick Share or AWDL. */
    private var opened: Participant? = null
    /** The link gave way to AWDL ([DeviceLinkRules.awdl]) and, if this side opened it, opens again once AWDL ends. */
    @Volatile private var yielded = false

    /** Listening: this side is registered for the other's messages, linked or not. */
    private val listening: Boolean get() = connection?.isClosed == false
    /** Channel is open and neither side unlinked (may still be waiting for the other side's first message). */
    private val active: Boolean get() = listening && !unlinked && !peerLeft
    /** Linked: channel is open and fresh battery data has been heard from the other side. */
    val linked: Boolean get() = active && status != null && SystemClock.elapsedRealtime() - statusAt <= DeviceLinkRules.STALE_MS
    /** Waiting: channel is open and listening, but no fresh data has arrived from the other side yet. */
    val waiting: Boolean get() = active && !linked

    /** The other side's devices as rows of Device batteries, besides [own]; none while not linked or when it went quiet. */
    fun devices(own: List<Device>): List<Device> {
        val s = status ?: return emptyList()
        if (!linked || SystemClock.elapsedRealtime() - statusAt > DeviceLinkRules.STALE_MS) return emptyList()
        return DeviceLinkRules.incoming(s, own)
    }

    /** The picker's question, for `ui/LinkActivity`: the user's other devices, the one picked woken with [ACTION_WAKE]. */
    fun pickerIntent(context: Context, title: String) = Dtdi.getClient(context.applicationContext)
        .createDevicePickerIntent(emptyList(), false, WakeUpRequest(ACTION_WAKE, emptyList(), title, emptyList()), null)

    /**
     * The device picked, from the picker's answer. Today's Play services answer in a newer layout than the SDK (2022)
     * reads: the devices picked are bundles under SAFE_DEVICES (`DEVICE_0`, …), each with its token (a binder) and name.
     */
    fun picked(context: Context, data: Intent?): Participant? {
        val extras = data?.extras ?: return null
        extras.classLoader = Link::class.java.classLoader
        val all = extras.getBundle(SAFE_DEVICES) ?: return null
        val device = all.getBundle(DEVICE_0) ?: return null
        var token: android.os.IBinder? = null
        var name: String? = null
        for (k in device.keySet()) {
            @Suppress("DEPRECATION")
            val value = runCatching { device.get(k) }.getOrNull()
            if (token == null && value is android.os.IBinder) token = value
            if (name == null && value is String) name = value
        }
        return token?.let { ParticipantImpl(name ?: "", it, CorrelationData.createNew(), Dtdi.getClient(context.applicationContext)) }
    }
    private const val SAFE_DEVICES = "com.google.android.gms.dtdi.discovery.SAFE_DEVICES"
    private const val DEVICE_0 = "com.google.android.gms.dtdi.core.extra.DEVICE_0"

    /** The user picked [participant]: open the link (Google wakes BentoBar there, which accepts). */
    fun open(participant: Participant) {
        opened = participant
        yielded = false
        unlinked = false
        peerLeft = false
        start(participant, notifyFailure = true, accepting = false)
    }

    /** Another device asked for a link (BentoBar was woken with [ACTION_WAKE]): accept it, unless AWDL is up here. */
    fun accept(participant: Participant) {
        if (busy && SystemClock.elapsedRealtime() - busyAt < DeviceLinkRules.LINGER_MS) {
            android.util.Log.i(TAG, "link refused: Quick Share or AWDL has the radio"); return
        }
        // Kept too: if Quick Share takes the radio here, this side asks the other again once it is done, since the other
        // side never hears why the link dropped.
        opened = participant
        yielded = false
        unlinked = false
        peerLeft = false
        start(participant, notifyFailure = false, accepting = true)
    }

    /** The names of the network interfaces that are up. Read without any permission; a failure reads as none. */
    private fun upInterfaces(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { runCatching { it.isUp }.getOrDefault(false) }.map { it.name }
    }.getOrDefault(emptyList())

    private fun awdlUp() = DeviceLinkRules.awdl(upInterfaces())

    /** What the last look found: something needs the radio (AWDL, or Quick Share in front), and when it last did. */
    @Volatile private var busy = false
    @Volatile private var busyAt = 0L
    /** Looks are made here: listing interfaces and reading usage events are calls into the system, kept off the main thread. */
    private val looker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "BentoBarLink").apply { isDaemon = true } }

    /**
     * Every [DeviceLinkRules.AWDL_EVERY_MS] while the link is open, opening or has given way: Quick Share in front or AWDL
     * up (an AirDrop to an Apple device) drops the link at once, so the radio is Quick Share's. While it gave way, the side
     * that opened the link opens it again a minute after both are gone; the other side waits to be asked.
     */
    private val awdlWatch = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            looker.execute {
                val awdl = awdlUp()
                val share = ScreenTimeLoad.inFront(Env.app, DeviceLinkRules.QUICK_SHARE)
                main.post { decide(awdl, share) }
            }
        }
    }

    private fun decide(awdl: Boolean, share: Boolean?) {
        val now = SystemClock.elapsedRealtime()
        busy = DeviceLinkRules.givesWay(awdl, share)
        if (busy) busyAt = now
        when {
            busy && (active || connecting) -> {
                android.util.Log.i(TAG, "${if (awdl) "AWDL is up" else "Quick Share is open"}: the link gives way")
                drop()
                yielded = true
            }
            yielded && DeviceLinkRules.relinks(busy, now - busyAt) -> {
                yielded = false
                opened?.let { participant ->
                    android.util.Log.i(TAG, "Quick Share and AWDL are done: linking again")
                    start(participant, notifyFailure = false, accepting = false)
                }
            }
        }
        if (active || connecting || yielded) main.postDelayed(awdlWatch, DeviceLinkRules.AWDL_EVERY_MS)
    }

    private fun watchAwdl() { main.removeCallbacks(awdlWatch); main.postDelayed(awdlWatch, DeviceLinkRules.AWDL_EVERY_MS) }

    private fun start(participant: Participant, notifyFailure: Boolean, accepting: Boolean) {
        val id = ++attempt
        val label = participant.displayName.toString()
        // Close any previous channel before opening a new one: Play services throws IllegalArgumentException if still registered.
        main.removeCallbacks(tick)
        connection?.let { runCatching { it.closeFuture() } }
        back?.let { runCatching { it.closeFuture() } }
        connection = null
        back = null
        peer = label
        connecting = true
        clearConnectTimeout()
        val timeout = Runnable {
            if (id != attempt || !connecting) return@Runnable
            connecting = false
            changed()
            android.util.Log.i(TAG, "link timed out")
            if (notifyFailure) showFailed(label)
        }
        connectTimeout = timeout
        main.postDelayed(timeout, DeviceLinkRules.CONNECT_TIMEOUT_MS)
        watchAwdl()
        changed()
        scope.launch {
            val result = runCatching {
                if (accepting) participant.acceptConnection(CHANNEL) else participant.openConnection(CHANNEL)
            }.getOrElse { Result.failure(it) }
            // The other direction ([back]); without it this side never hears a link the other side opened itself.
            val reverse = if (result.isFailure) null else runCatching {
                if (accepting) participant.openConnection(CHANNEL) else participant.acceptConnection(CHANNEL)
            }.getOrNull()?.getOrNull()
            main.post {
                if (id != attempt || !connecting) {
                    result.getOrNull()?.let { runCatching { it.closeFuture() } }
                    reverse?.let { runCatching { it.closeFuture() } }
                    return@post
                }
                clearConnectTimeout()
                result.onSuccess { use(it, reverse) }.onFailure {
                    android.util.Log.i(TAG, "link failed: ${it.javaClass.simpleName}")
                    if (notifyFailure) showFailed(label)
                }
                connecting = false
                changed()
            }
        }
    }

    private fun clearConnectTimeout() {
        connectTimeout?.let { main.removeCallbacks(it) }
        connectTimeout = null
    }

    private fun showFailed(label: String) {
        Toast.makeText(Env.app, Env.app.getString(R.string.link_could_not_connect, label), Toast.LENGTH_SHORT).show()
    }

    private fun use(c: RemoteConnection, reverse: RemoteConnection?) {
        peer?.takeIf { it.isNotBlank() }?.let { name ->
            known = name
            looker.execute { runCatching { com.meridian11.books.bentobar.data.Kept.own(KEPT).write(PEER_NAME, name, System.currentTimeMillis()) } }
        }
        connection?.takeIf { it !== c }?.let { old -> runCatching { old.closeFuture() } }
        back?.takeIf { it !== reverse }?.let { old -> runCatching { old.closeFuture() } }
        connection = c
        back = reverse
        linkedAt = SystemClock.elapsedRealtime()
        status = null
        c.registerReceiver(receiver)
        reverse?.registerReceiver(receiver)
        android.util.Log.i(TAG, "linked")
        main.removeCallbacks(tick)
        tick.run()
    }

    /**
     * Drops the link (Device batteries' Unlink, the tile): tells the other side so it stops too, then closes the
     * channels and stays unlinked until the user picks a device again.
     */
    fun unlink() {
        yielded = false
        unlinked = true
        opened = null
        main.removeCallbacks(awdlWatch)
        val c = connection
        if (c == null) { drop(); return }
        // Update the UI at once, tell the other side to drop too, then close the channels.
        status = null
        changed()
        scope.launch {
            runCatching { c.send(DeviceLinkRules.BYE.toByteArray()) }
            main.post { drop() }
        }
    }

    /** Links again to the device last linked, without the picker (the tile). False when there is none to ask. */
    fun relink(quiet: Boolean = false): Boolean {
        val participant = opened ?: return false
        if (active || connecting) return true
        yielded = false
        unlinked = false
        if (!quiet) peerLeft = false
        start(participant, notifyFailure = !quiet, accepting = false)
        return true
    }

    /**
     * Device batteries' tick: a link the other side opened (BentoBar's card or "Link again" there) wakes nothing here,
     * so while the item is live, the bar knows a device it was linked to and isn't linked now, it looks for that device
     * every [DeviceLinkRules.FOLLOW_MS] (the same short scan as "Link again", quietly) and links back. Google Play services
     * keep what the other side sent meanwhile. Not after the user unlinked here, nor while Quick Share has the radio.
     */
    fun follow(now: Long) {
        if (listening || connecting || unlinked || peerLeft || yielded || busy) return
        if (followedAt != 0L && now - followedAt < DeviceLinkRules.FOLLOW_MS) return
        followedAt = now
        if (known == null && opened == null) {
            // The kept name is read off the main thread once; a later tick follows with it.
            followedAt = 0L
            looker.execute { lastPeer() }
            return
        }
        autolink(quiet = true) {}
    }

    private const val KEPT = "link"
    private const val PEER_NAME = "peer"
    /** How long [autolink] looks for the last device before it gives up. */
    private const val FIND_MS = 10_000L

    /** The name of the device last linked, kept across restarts; null if none ever was. Read from its file once. */
    private fun lastPeer(): String? = known ?: runCatching { com.meridian11.books.bentobar.data.Kept.own(KEPT).read(PEER_NAME)?.text }
        .getOrNull()?.takeIf { it.isNotBlank() }?.also { known = it }
    @Volatile private var known: String? = null

    /** The device to link again to, for the menu's words: the one this process last saw, else the one kept. */
    fun lastName(): String? = peer ?: lastPeer()

    /**
     * The tile's tap: links to the device last linked, with no picker. From memory while this process remembers it;
     * after a restart, by looking for a device of the kept name among the user's own (Google Play services' scan,
     * [FIND_MS] at most). [onMissing] runs on the main thread when there is no such device to ask: none was ever
     * linked, or it wasn't found in time.
     */
    fun autolink(quiet: Boolean = false, onMissing: () -> Unit) {
        if (active || connecting) return
        if (!quiet) unlinked = false
        if (relink(quiet)) return
        val name = lastPeer() ?: return onMissing()
        // The scan asks for Nearby devices (BLUETOOTH_SCAN), which the picker's first use asks the user for (LinkActivity).
        if (!canScan()) return onMissing()
        val client = runCatching { Dtdi.getClient(Env.app) }.getOrNull() ?: return onMissing()
        val id = ++attempt
        peer = name
        connecting = true
        changed()
        var settled = false
        val giveUp = Runnable {
            if (settled) return@Runnable
            settled = true
            runCatching { client.stopDiscovery() }
            if (id == attempt) { connecting = false; changed() }
            if (!quiet) android.util.Log.i(TAG, "autolink: last device not found")
            onMissing()
        }
        main.postDelayed(giveUp, FIND_MS)
        val listener = object : com.google.android.gms.dtdi.core.OnScanResultListener {
            override fun onScanResult(result: com.google.android.gms.dtdi.core.ScanResult) {
                val device = result.remoteDevices.firstOrNull { it.deviceName == name } ?: return
                main.post {
                    if (settled) return@post
                    settled = true
                    main.removeCallbacks(giveUp)
                    runCatching { client.stopDiscovery() }
                    if (id != attempt) return@post // unlinked meanwhile
                    connecting = false
                    android.util.Log.i(TAG, "autolink: found the last device")
                    val participant = ParticipantImpl(device.deviceName, device.token, CorrelationData.createNew(), client)
                    if (quiet) { opened = participant; yielded = false; start(participant, notifyFailure = false, accepting = false) }
                    else open(participant)
                }
            }
        }
        runCatching {
            @android.annotation.SuppressLint("MissingPermission") // canScan() above
            val scan = client.startOrUpdateDiscovery(emptyList(), com.google.android.gms.dtdi.core.DiscoveryFeature.CROSS_DEVICE_SDK, listener)
            scan
                .addOnFailureListener { e -> android.util.Log.i(TAG, "autolink scan failed: ${e.javaClass.simpleName}"); main.post(giveUp) }
        }.onFailure { main.post(giveUp) }
    }

    /** Forgets the saved linked device so the menu opens Google's device picker instead of linking to it again. */
    fun forget() {
        yielded = false
        unlinked = false
        peerLeft = false
        main.removeCallbacks(awdlWatch)
        opened = null
        peer = null
        known = null
        looker.execute { runCatching { com.meridian11.books.bentobar.data.Kept.own(KEPT).remove(PEER_NAME) } }
        drop()
    }

    /** Whether the scan [autolink] needs is allowed: Nearby devices' BLUETOOTH_SCAN. */
    fun canScan(): Boolean = Env.app.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Gave way to Quick Share or AWDL, and will link again once they are done. */
    val paused: Boolean get() = yielded && !linked && !connecting

    /** The bar's items and the tile hear of every change of the link. */
    private fun changed() {
        Ticker.refresh()
        com.meridian11.books.bentobar.tile.LinkTile.refresh(Env.app)
    }

    private fun drop() {
        attempt++
        clearConnectTimeout()
        connecting = false
        main.removeCallbacks(tick)
        connection?.let { c -> runCatching { c.closeFuture() } }
        back?.let { c -> runCatching { c.closeFuture() } }
        connection = null
        back = null
        status = null
        changed()
    }

    private val tick = object : Runnable {
        override fun run() {
            val c = connection ?: return
            if (c.isClosed) return dropped(c)
            // The other side's BentoBar can end (Android stops it, a restart) and link again with no word to this side,
            // whose channels then hear nothing while it still counts as linked, and so never looks again: a link that
            // stays quiet is dropped, and [follow] finds the device anew with a fresh scan.
            val now = SystemClock.elapsedRealtime()
            if (!active) { main.postDelayed(this, DeviceLinkRules.EVERY_MS); return } // listening only: nothing to send
            if (DeviceLinkRules.quiet(now, linkedAt, statusAt)) {
                val participant = opened
                if (!requiet && participant != null) {
                    // First registered again on the same device, which costs nothing; quiet again, looked for anew.
                    android.util.Log.i(TAG, "link quiet: registering again")
                    requiet = true
                    drop()
                    start(participant, notifyFailure = false, accepting = false)
                } else {
                    android.util.Log.i(TAG, "link quiet: looking again")
                    requiet = false
                    drop()
                    opened = null
                    followedAt = 0L
                }
                return
            }
            val text = runCatching { DeviceLinkRules.encode(own()) }.getOrNull()
            if (text != null) scope.launch {
                val sent = runCatching { c.send(text.toByteArray()) }.getOrElse { Result.failure(it) }
                sent.exceptionOrNull()?.let { android.util.Log.i(TAG, "send failed: ${it.javaClass.simpleName}") }
                    ?: run { sends++; if (sends == 1 || sends % 10 == 0) android.util.Log.i(TAG, "sent $sends") }
            }
            main.postDelayed(this, DeviceLinkRules.EVERY_MS)
        }
    }

    private val receiver = object : ConnectionReceiver {
        override fun onMessageReceived(remoteConnection: RemoteConnection, payload: ByteArray) {
            val text = String(payload)
            if (text == DeviceLinkRules.BYE) {
                main.post {
                    if (remoteConnection !== connection && remoteConnection !== back) return@post
                    android.util.Log.i(TAG, "the other side unlinked")
                    peerLeft = true
                    opened = null
                    yielded = false
                    main.removeCallbacks(awdlWatch)
                    drop()
                }
                return
            }
            val got = DeviceLinkRules.decode(text)
            main.post {
                val which = when (remoteConnection) { connection -> "main"; back -> "back"; else -> "old" }
                heard++
                if (heard == 1 || heard % 10 == 0 || which == "old" || got == null)
                    android.util.Log.i(TAG, "heard $heard on $which${if (got == null) " (unreadable)" else ""}")
                if (got == null) return@post
                if (remoteConnection !== connection && remoteConnection !== back) return@post
                // The home screen widgets show the other side too: they hear of what changed in it.
                if (got != status) com.meridian11.books.bentobar.widget.WidgetUpdater.changed()
                // The other side speaks: it linked (its card, "Link again", or back from an unlink), so this side does
                // too, and answers at once, so that both show each other with no wait.
                val was = linked
                unlinked = false
                peerLeft = false
                requiet = false
                status = got
                statusAt = SystemClock.elapsedRealtime()
                if (!was) { android.util.Log.i(TAG, "the other side linked"); main.removeCallbacks(tick); tick.run() }
                changed()
            }
        }

        override fun onConnectionClosed(remoteConnection: RemoteConnection, error: Throwable?, reason: String?) {
            main.post {
                val which = when (remoteConnection) { connection -> "main"; back -> "back"; else -> "old" }
                android.util.Log.i(TAG, "closed: $which ${error?.javaClass?.simpleName ?: ""}")
                dropped(remoteConnection)
            }
        }
    }

    private fun dropped(c: RemoteConnection) {
        if (c !== connection) return
        android.util.Log.i(TAG, "link closed")
        main.removeCallbacks(tick)
        back?.let { b -> runCatching { b.closeFuture() } }
        back = null
        connection = null
        // The other side's BentoBar ended (Android stopped it, an update) or it linked again: this side registers again
        // on the same device at once, so that it hears the other as soon as it is back. Closes in a row wait a while.
        val participant = opened
        if (participant != null && !yielded && !unlinked && !peerLeft) {
            val now = SystemClock.elapsedRealtime()
            val wait = if (now - relistenedAt < DeviceLinkRules.RELISTEN_GAP_MS) DeviceLinkRules.RELISTEN_WAIT_MS else 0L
            relistenedAt = now + wait
            main.postDelayed({
                if (!listening && !connecting && !yielded && !unlinked && !peerLeft && opened === participant) start(participant, notifyFailure = false, accepting = true)
            }, wait)
        }
        status = null
        changed()
    }

    /** This side's status: its own battery, its name and kind, and the devices Device batteries found here. */
    private fun own(): DeviceLinkRules.Status {
        val app = Env.app
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let { b ->
            val l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && scale > 0) l * 100 / scale else null
        }
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1).let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        }
        val name = runCatching { Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        val kind = if (app.resources.configuration.smallestScreenWidthDp >= 600) DevicesRules.Kind.TABLET else DevicesRules.Kind.PHONE
        val speed = battery?.takeIf { plugged && it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 }
            ?.let { ChargeSpeed.of(it.getIntExtra("charger_type", 0), it.getBooleanExtra("hv_charger", false)) }
        return DeviceLinkRules.Status(DeviceLinkRules.Battery(kind, name, level, plugged, speed = speed), DeviceLinkRules.outgoing(DevicesItem.found()))
    }

    /** The test hook: whether linked, and what the other side sent (kinds and levels, no names). */
    fun debugLine(): String {
        val s = status
        return "linked=$linked connecting=$connecting busy=$busy yielded=$yielded peer=${peer != null} " +
            (s?.let { st -> (listOf(st.self) + st.devices).joinToString(" ") { "${it.kind.name.lowercase()}=${it.level ?: "?"}${if (it.charging) "c" else ""}" } } ?: "nothing")
    }
}
