package com.meridian11.books.bentobar.items

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.DevicesRules.Device
import com.meridian11.books.bentobar.ui.InfoRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.MainActivity
import com.meridian11.books.bentobar.ui.Meter
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon

/**
 * Device batteries: the battery of a mouse, keyboard, stylus or game controller that tells Android
 * its level, and of connected Bluetooth headphones. Input devices need no permission: they report it
 * themselves. Headphones need BLUETOOTH_CONNECT, asked for only from the menu's "Show headphones";
 * without it they are not listed, nor is a device that reports no level.
 *
 * While an item of this type is live, each input device is asked for its battery once a minute, on a
 * background thread, and Android is listened to for devices being connected, changed or removed:
 * that, and never what a device types or clicks. Idle, nothing is asked and nothing is listened to.
 * Which devices are listed and what the bar makes of them is in [DevicesRules].
 */
object DevicesItem : ItemType("devices", R.string.item_devices_title, Sym.MOUSE, R.string.item_devices_desc) {
    override val refreshMs = 5_000L
    override val canBeActive = true
    override val addsWhenActive = true

    /** The Battery item's rule, but up to 100%: earbuds drain fast, and some want them in the bar well before they are low. */
    private val below = Threshold("lowPct", 20, 5..100, step = 5) { "$it%" }
    override val trigger = Trigger(R.string.trigger_devices, R.string.trigger_devices_short, below)

    private val words = DevicesRules.Words(
        none = { Env.str(R.string.devices_none) },
        level = { name, percent -> Env.plural(R.plurals.devices_desc, percent, name, percent) },
        low = { name, percent -> Env.plural(R.plurals.devices_desc_low, percent, name, percent) },
        charging = { name, percent -> Env.plural(R.plurals.devices_desc_charging, percent, name, percent) },
        unknown = { name -> Env.str(R.string.devices_desc_unknown, name) },
        unnamed = { Env.str(R.string.common_unknown) },
    )

    /**
     * What the devices last said, read again when it is a minute old and whenever [watch] asks. A
     * device's battery is asked for across into the system, which can wait for the Bluetooth stack:
     * it is the loader's background thread that asks.
     */
    private val readings = Background.refresher<Unit, List<Device>>(type, every = { _, _ -> DevicesRules.EVERY_MS },
        load = { _, _ -> read().also { DevicesProvider.read(it) } })
    // At most one reading a second, however often Android reports: one that can't be taken now (another is under
    // way, or the last is under a second old) is asked for again on the next tick, never queued behind the other.
    private val watch = DevicesWatch { !readings.loading(Unit) && readings.refresh(Unit, floorMs = DevicesRules.FLOOR_MS) }
    private val main = Handler(Looper.getMainLooper())
    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = changed()
        override fun onInputDeviceRemoved(deviceId: Int) = changed()
        override fun onInputDeviceChanged(deviceId: Int) = changed()
    }
    /** Bluetooth devices connecting, going, or reporting a new battery level: read again. */
    private val bluetooth = object : BroadcastReceiver() {
        // Which earbuds are in is read again too, not a minute later.
        override fun onReceive(context: Context, intent: Intent) { RootBt.forgetSides(); changed() }
    }
    private var hearing = false

    /** An item of this type is being sampled. Only then is anything read: a state asked for in passing reads nothing. */
    @Volatile private var live = false
    private var listening = false
    /** The test hook's devices, shown instead of the real ones, which are then not read; null: none staged. */
    @Volatile private var staged: List<Device>? = null

    private fun input(): InputManager? = Env.app.getSystemService(InputManager::class.java)

    /** A device came, went or changed, or nobody had been listening: read now, and once more a moment later. */
    private fun changed() { if (live && staged == null) watch.changed(Now.elapsed()) }

    override fun onLive() {
        live = true
        // What was read before the bar went away is shown while it is read again, so that the item doesn't blink
        // out and in each time the bar returns; unless that was long ago, when a device may be long gone.
        if (DevicesRules.tooOld(readings.age(Unit))) readings.forget()
        val input = input()
        if (input != null && !listening) listening = runCatching { input.registerInputDeviceListener(listener, main) }.isSuccess
        if (leAudio == null && headphonesAllowed()) runCatching {
            Env.app.getSystemService(BluetoothManager::class.java)?.adapter
                ?.getProfileProxy(Env.app, leListener, android.bluetooth.BluetoothProfile.LE_AUDIO)
        }
        if (!hearing && headphonesAllowed()) hearing = runCatching {
            Env.app.registerReceiver(bluetooth, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED); addAction(BATTERY_LEVEL_CHANGED)
                // An earbud put in its case may only leave LE Audio, or the music or call audio: those say so.
                addAction(android.bluetooth.BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED)
                addAction(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            }, Context.RECEIVER_EXPORTED)
        }.isSuccess
        changed()
    }

    override fun onIdle() {
        live = false
        if (listening) { runCatching { input()?.unregisterInputDeviceListener(listener) }; listening = false }
        if (hearing) { runCatching { Env.app.unregisterReceiver(bluetooth) }; hearing = false }
        leAudio?.let { p -> runCatching { Env.app.getSystemService(BluetoothManager::class.java)?.adapter
            ?.closeProfileProxy(android.bluetooth.BluetoothProfile.LE_AUDIO, p) } }
        leAudio = null
        watch.clear()
    }

    override fun sample(now: Long) { if (staged == null) watch.tick(now) }

    /**
     * Every input device with what it says of its battery, as the devices to list. Background thread.
     * A device that goes away under the reading doesn't take the others with it, and no name is logged.
     */
    private fun read(): List<Device> = runCatching {
        val input = input() ?: return emptyList()
        val seen = ArrayList<DevicesRules.Seen>()
        for (id in input.inputDeviceIds) runCatching {
            val device = input.getInputDevice(id)
            if (device != null && !device.isVirtual) {
                val battery = device.batteryState
                seen += DevicesRules.Seen(device.descriptor.orEmpty(), device.name.orEmpty(), device.sources, device.keyboardType,
                    battery.isPresent, battery.capacity, battery.status)
            }
        }
        DevicesRules.merge(DevicesRules.devices(seen), DevicesRules.headphones(runCatching { audio() }.getOrDefault(emptyList())))
    }.getOrDefault(emptyList())

    /** Whether headphones may be read: the user allowed seeing Bluetooth devices. */
    fun headphonesAllowed() = Env.app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * The connected Bluetooth devices that can have a battery (headphones, earbuds, speakers, mice, keyboards,
     * controllers) with the level they report. Background
     * thread. Whether one is connected and its level are not in the SDK's API: they are asked for by name, and a
     * device that can't say is left out. No name or address is logged.
     */
    private fun audio(): List<DevicesRules.Audio> {
        if (!headphonesAllowed()) return emptyList()
        val adapter = Env.app.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        val isConnected = BluetoothDevice::class.java.getMethod("isConnected")
        val batteryLevel = BluetoothDevice::class.java.getMethod("getBatteryLevel")
        return adapter.bondedDevices.orEmpty().mapNotNull { d ->
            runCatching {
                val cls = d.bluetoothClass?.deviceClass ?: 0
                if (DevicesRules.kindOf(cls) == null) null
                else if (isConnected.invoke(d) != true) null
                else {
                    val kind = DevicesRules.kindOf(cls)
                    var level = batteryLevel.invoke(d) as? Int ?: -1
                    // With root, Samsung's companion battery list (watches, Buds): live, and says when one charges. A watch's
                    // level comes from it first (Android has none), then from the Galaxy Wearable plugin's records.
                    val companion = RootBt.companion(d.address.orEmpty())
                    // The earbuds' case, while Samsung knows its level: kept, to show until the next reading.
                    if (kind == DevicesRules.Kind.HEADPHONES) companion?.case?.let { Cases.saw(d.alias ?: d.name.orEmpty(), it, companion.caseCharging) }
                    if (kind == DevicesRules.Kind.WATCH) level = companion?.level ?: level.takeIf { it in 0..100 } ?: RootBt.watchLevel(d.address.orEmpty()) ?: -1
                    DevicesRules.Audio(d.address.orEmpty(), d.alias ?: d.name.orEmpty(), level,
                        main = d.type != BluetoothDevice.DEVICE_TYPE_LE, deviceClass = cls,
                        side = if (kind == DevicesRules.Kind.HEADPHONES) RootBt.side(d.address.orEmpty()) else null,
                        earbud = kind == DevicesRules.Kind.HEADPHONES && runCatching { leAudio?.connectedDevices?.contains(d) == true }.getOrDefault(false),
                        inUse = if (kind == DevicesRules.Kind.HEADPHONES) leAudio?.let { p ->
                            runCatching { p.getConnectionState(d) == android.bluetooth.BluetoothProfile.STATE_CONNECTED }.getOrNull()
                        } else null,
                        // Earbuds charging in their case say so one by one, not in the pair's own status.
                        charging = companion?.charging == true || companion?.chargingEars != null,
                        chargingEars = if (kind == DevicesRules.Kind.HEADPHONES) companion?.chargingEars else null)
                }
            }.getOrNull()
        }
    }

    /** LE Audio, for the ear each earbud is for; asked for while an item is live, and let go when it is idle. */
    @Volatile private var leAudio: android.bluetooth.BluetoothLeAudio? = null
    private val leListener = object : android.bluetooth.BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
            leAudio = proxy as? android.bluetooth.BluetoothLeAudio
            changed()
        }
        override fun onServiceDisconnected(profile: Int) { leAudio = null }
    }

    /** Sent by Bluetooth when a device reports a new battery level (not in the SDK by name). */
    private const val BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"

    /**
     * The devices to show: the staged ones, else what was last read, which is read again when it has grown old; and
     * those a linked device told of ([Link]). Main thread.
     */
    private fun devices(): List<Device> {
        staged?.let { return it }
        if (live) readings.want(Unit)
        val own = readings.peek(Unit).orEmpty()
        return DevicesRules.order(own + Link.devices(own))
    }

    /** The devices found on this device (none a link told of): what [Link] sends the other side. */
    fun found(): List<Device> = staged ?: readings.peek(Unit).orEmpty()

    private val shareLock = Any()
    @Volatile private var sharedAt = 0L
    @Volatile private var shared: List<Device> = emptyList()

    /**
     * What another app reads ([DevicesProvider]): the devices found on this device. What the bar read is handed out
     * while it is under [DevicesShareRules.FRESH_MS] old; else they are read now, once for every asker in that time,
     * even while no item is live (an app asked, and the user let it). The provider's binder thread, never the main one.
     */
    fun forOtherApps(): List<Device> {
        staged?.let { return it }
        readings.age(Unit)?.let { if (it < DevicesShareRules.FRESH_MS) return readings.peek(Unit).orEmpty() }
        synchronized(shareLock) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (sharedAt != 0L && now - sharedAt < DevicesShareRules.FRESH_MS) return shared
            shared = read()
            sharedAt = now
            DevicesProvider.read(shared)
            return shared
        }
    }

    override fun state(item: ItemConfig): ItemState {
        val shown = DevicesRules.bar(devices(), below.of(item), words, item.options[DevicesRules.PINNED])
        return ItemState(icon = shown.glyph, filled = shown.filled, text = shown.text, tone = shown.tone, active = shown.active,
            desc = shown.desc, tooltip = shown.tooltip, iconColor = shown.speed?.color)
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        rememberTick()
        // Whoever opens the menu looks at the levels: read them again, unless they were read a moment ago.
        LaunchedEffect(Unit) { if (live && staged == null) readings.refresh(Unit, floorMs = DevicesRules.MENU_FLOOR_MS) }
        val devices = devices()
        MenuCard(Sym.MOUSE, stringResource(R.string.item_devices_title),
            if (devices.isEmpty()) stringResource(R.string.devices_none) else pluralStringResource(R.plurals.devices_count, devices.size, devices.size)) {
            // A tap keeps that device in the bar; another tap on it goes back to the lowest.
            val pinned = item.options[DevicesRules.PINNED]
            devices.forEach { d ->
                MeterRow(d, DevicesRules.name(d, words), DevicesRules.desc(d, words), pinned = d.name == pinned) {
                    Store.updateItem(item.id) { it.with(DevicesRules.PINNED, if (d.name == pinned) null else d.name) }
                }
            }
            // The earbuds' cases as last read, also while the earbuds are away: "Buds case 72% · 3h 05m ago".
            val cases = Cases.all()
            if (cases.isNotEmpty()) {
                MenuDivider()
                com.meridian11.books.bentobar.ui.SectionLabel(stringResource(R.string.devices_cases))
                val now = System.currentTimeMillis()
                cases.forEach { c ->
                    val level = DevicesRules.percentText(c.level)
                    InfoRow(stringResource(R.string.devices_case_of, c.name),
                        if (now - c.at < DevicesRules.CASE_FRESH_MS) level else stringResource(R.string.devices_case_seen, level, Fmt.duration(now - c.at)))
                }
            }
            Spacer(Modifier.height(6.dp))
            if (devices.size > 1) MenuNote(stringResource(if (pinned != null && devices.any { it.name == pinned })
                R.string.devices_pinned_note else R.string.devices_pin_note))
            // The note is what makes an empty menu honest: it says what would show here, and why headphones never do.
            val allowed = headphonesAllowed()
            MenuNote(stringResource(if (allowed) R.string.devices_note else R.string.devices_note_no_headphones))
            MenuDivider()
            if (!allowed) MenuEntry(Sym.HEADPHONES, stringResource(R.string.devices_show_headphones)) {
                host.close(); MainActivity.requestPermission(Env.app, Manifest.permission.BLUETOOTH_CONNECT)
            }
            MenuEntry(Sym.BLUETOOTH, stringResource(R.string.devices_bluetooth_settings)) { host.close(); Env.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            // The link to the user's other device: its batteries here, and this one's there.
            val peer = Link.peer
            when {
                Link.linked && peer != null -> {
                    MenuNote(stringResource(R.string.link_linked, peer))
                    MenuEntry(Sym.LINK, stringResource(R.string.link_unlink)) { Link.unlink() }
                }
                Link.connecting && peer != null -> MenuNote(stringResource(R.string.link_connecting, peer))
                else -> MenuEntry(Sym.LINK, if (peer != null) stringResource(R.string.link_again, peer) else stringResource(R.string.link_open)) {
                    host.close(); Env.launch(Intent(Env.app, com.meridian11.books.bentobar.ui.LinkActivity::class.java))
                }
            }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        rememberTick()
        val pinned = item.options[DevicesRules.PINNED]
        // The devices there are now, and the one picked even while it is away.
        val names = (devices().map { it.name }.filter { it.isNotBlank() } + listOfNotNull(pinned)).distinct()
        com.meridian11.books.bentobar.ui.ChoiceRow(stringResource(R.string.devices_in_bar),
            listOf<String?>(null).map { it to stringResource(R.string.devices_in_bar_lowest) } + names.map { it to it },
            pinned, help = stringResource(R.string.devices_in_bar_help)) { v -> set(item.with(DevicesRules.PINNED, v)) }
        // For this install, not this item: the switch is kept outside the layout (data/Sharing).
        val sharing by com.meridian11.books.bentobar.data.Sharing.on.collectAsState()
        com.meridian11.books.bentobar.ui.SwitchRow(stringResource(R.string.devices_share), sharing,
            help = stringResource(R.string.devices_share_help)) { com.meridian11.books.bentobar.data.Sharing.set(it) }
    }

    /**
     * The test hook (debug builds): `stage mouse=15 keyboard=85c stylus=?` shows these devices instead
     * of the real ones (c: charging; ? or `unknown`: a battery without a level; `mouse:MX_Master_3S=8`
     * to name one), `stage none` no device at all, `off` the real ones again. Without a word: what is
     * shown now.
     */
    override fun debug(args: List<String>): String? {
        when (args.firstOrNull()) {
            null -> return line()
            "stage" -> staged = DevicesRules.staged(args.drop(1))
                ?: return "devices stage none | KIND[:NAME]=LEVEL[c] …, for example mouse=15 keyboard:Keychron_K3=85c stylus=unknown"
            "off" -> { staged = null; changed() }
            // What root knows of the ears and the watches: to the log, without addresses.
            "root" -> return "sides=${RootBt.debugSides()} watches=${RootBt.debugWatches()}"
            // The link to another device: whether linked, and the kinds and levels it sent.
            "link" -> return Link.debugLine()
            else -> return null
        }
        Ticker.refresh()
        return line()
    }

    /** Kinds and levels in the hook's own words, and no names: a device's name can be a person's. */
    private fun line(): String {
        val devices = staged ?: readings.peek(Unit).orEmpty()
        return "live=$live listening=$listening staged=${staged != null} devices=${devices.size}" +
            devices.joinToString("") { " ${it.kind.name.lowercase()}=${it.percent ?: "?"}${if (it.charging) "c" else ""}" } +
            " shown=${DevicesRules.shown(devices)?.kind?.name?.lowercase() ?: "none"}"
    }
}

/**
 * One device and its battery: its glyph, its name and its level, and under them a meter that starts
 * where the name does. Low (under 20% and not charging), the level and the meter are in the error
 * color; a battery that doesn't say its level has the words for that and no meter. To a screen
 * reader the row is one sentence, [spoken], which says "low" in words.
 */
@Composable
private fun MeterRow(device: Device, name: String, spoken: String, pinned: Boolean = false, onClick: (() -> Unit)? = null) {
    val low = DevicesRules.low(device)
    // The meter as the Battery menu's: green down to 20%, orange to 15%, red below.
    val band = device.percent?.let { BatteryBand.of(it / 100.0) }
    val bandColor = when (band) {
        BatteryBand.CRITICAL -> BatteryBand.RED
        BatteryBand.LOW -> BatteryBand.ORANGE
        else -> BatteryBand.GREEN
    }
    val percent = device.percent
    Column(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = 6.dp).clearAndSetSemantics { contentDescription = spoken }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                SymIcon(DevicesRules.glyph(device), size = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            // The device kept in the bar: a pin.
            if (pinned) {
                SymIcon(Sym.PUSH_PIN, size = 14.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
            }
            // Charging: a bolt before the level ("74% · Charging" says it in words too).
            if (device.charging && percent != null) {
                SymIcon(device.speed?.glyph ?: Sym.BOLT, size = 14.sp, color = device.speed?.color ?: BatteryBand.GREEN)
                Spacer(Modifier.width(4.dp))
            }
            if (percent == null) Text(stringResource(R.string.devices_unknown), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            else {
                val level = DevicesRules.percentText(percent)
                Text(if (device.charging) stringResource(R.string.devices_level_charging, level) else level,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.Medium,
                    color = if (band == BatteryBand.FULL || band == null) MaterialTheme.colorScheme.onSurface else bandColor, maxLines = 1)
            }
        }
        if (percent != null) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.padding(start = 30.dp)) { Meter(percent / 100f, bandColor) }
        }
    }
}
