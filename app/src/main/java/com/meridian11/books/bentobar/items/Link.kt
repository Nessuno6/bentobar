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

    val linked: Boolean get() = connection?.isClosed == false

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
        start(participant, notifyFailure = true) { participant.openConnection(CHANNEL) }
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
        start(participant, notifyFailure = false) { participant.acceptConnection(CHANNEL) }
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
            busy && (linked || connecting) -> {
                android.util.Log.i(TAG, "${if (awdl) "AWDL is up" else "Quick Share is open"}: the link gives way")
                drop()
                yielded = true
            }
            yielded && DeviceLinkRules.relinks(busy, now - busyAt) -> {
                yielded = false
                opened?.let { participant ->
                    android.util.Log.i(TAG, "Quick Share and AWDL are done: linking again")
                    start(participant, notifyFailure = false) { participant.openConnection(CHANNEL) }
                }
            }
        }
        if (linked || connecting || yielded) main.postDelayed(awdlWatch, DeviceLinkRules.AWDL_EVERY_MS)
    }

    private fun watchAwdl() { main.removeCallbacks(awdlWatch); main.postDelayed(awdlWatch, DeviceLinkRules.AWDL_EVERY_MS) }

    private fun start(participant: Participant, notifyFailure: Boolean, how: suspend () -> Result<RemoteConnection>) {
        val id = ++attempt
        val label = participant.displayName.toString()
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
            val result = runCatching { how() }.getOrElse { Result.failure(it) }
            main.post {
                if (id != attempt || !connecting) {
                    result.getOrNull()?.let { runCatching { it.closeFuture() } }
                    return@post
                }
                clearConnectTimeout()
                result.onSuccess { use(it) }.onFailure {
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

    private fun use(c: RemoteConnection) {
        peer?.takeIf { it.isNotBlank() }?.let { name ->
            known = name
            looker.execute { runCatching { com.meridian11.books.bentobar.data.Kept.own(KEPT).write(PEER_NAME, name, System.currentTimeMillis()) } }
        }
        connection?.takeIf { it !== c }?.let { old -> runCatching { old.closeFuture() } }
        connection = c
        status = null
        c.registerReceiver(receiver)
        android.util.Log.i(TAG, "linked")
        main.removeCallbacks(tick)
        tick.run()
    }

    /**
     * Drops the link (Device batteries' Unlink, the tile): it stays down until asked again, sharing or not. The device is
     * remembered, so the tile links it again with one tap.
     */
    fun unlink() {
        yielded = false
        main.removeCallbacks(awdlWatch)
        drop()
    }

    /** Links again to the device last linked, without the picker (the tile). False when there is none to ask. */
    fun relink(): Boolean {
        val participant = opened ?: return false
        if (linked || connecting) return true
        yielded = false
        start(participant, notifyFailure = true) { participant.openConnection(CHANNEL) }
        return true
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
    fun autolink(onMissing: () -> Unit) {
        if (linked || connecting) return
        if (relink()) return
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
            android.util.Log.i(TAG, "autolink: last device not found")
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
                    open(ParticipantImpl(device.deviceName, device.token, CorrelationData.createNew(), client))
                }
            }
        }
        runCatching {
            @android.annotation.SuppressLint("MissingPermission") // canScan() above
            val scan = client.startOrUpdateDiscovery(emptyList(), 0, listener)
            scan
                .addOnFailureListener { e -> android.util.Log.i(TAG, "autolink scan failed: ${e.javaClass.simpleName}"); main.post(giveUp) }
        }.onFailure { main.post(giveUp) }
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
        connection = null
        status = null
        changed()
    }

    private val tick = object : Runnable {
        override fun run() {
            val c = connection ?: return
            if (c.isClosed) return dropped(c)
            val text = runCatching { DeviceLinkRules.encode(own()) }.getOrNull()
            if (text != null) scope.launch { c.send(text.toByteArray()) }
            main.postDelayed(this, DeviceLinkRules.EVERY_MS)
        }
    }

    private val receiver = object : ConnectionReceiver {
        override fun onMessageReceived(remoteConnection: RemoteConnection, payload: ByteArray) {
            val got = DeviceLinkRules.decode(String(payload)) ?: return
            main.post {
                if (remoteConnection !== connection) return@post
                // The home screen widgets show the other side too: they hear of what changed in it.
                if (got != status) com.meridian11.books.bentobar.widget.WidgetUpdater.changed()
                status = got
                statusAt = SystemClock.elapsedRealtime()
                changed()
            }
        }

        override fun onConnectionClosed(remoteConnection: RemoteConnection, error: Throwable?, reason: String?) {
            main.post { dropped(remoteConnection) }
        }
    }

    private fun dropped(c: RemoteConnection) {
        if (c !== connection) return
        android.util.Log.i(TAG, "link closed")
        main.removeCallbacks(tick)
        connection = null
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
