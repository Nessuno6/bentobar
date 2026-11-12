package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.util.Sym
import kotlin.math.roundToInt

/**
 * The rules of Device batteries that need no Android: what kind of device an input device is, which
 * devices are listed and in what order, which one the bar shows, and what it says. Pure Kotlin,
 * unit-tested (`DevicesRulesTest`). What Android's `InputDevice` and `BatteryState` say comes in as
 * the plain numbers they are.
 */
object DevicesRules {
    /** The devices are asked for their battery once a minute; Android tells of a device that comes or goes, not of a level that changes. */
    const val EVERY_MS = 60_000L
    /** No second reading within a second of the last: a device that connects reports in two or three times in a row. */
    const val FLOOR_MS = 1_000L
    /** A device that has just connected is asked once more after this long: it often tells its level a moment later. */
    const val SETTLE_MS = 5_000L
    /** Opening the menu reads the levels again, unless the last reading is younger than this. */
    const val MENU_FLOOR_MS = 5_000L
    /** The tones' own numbers, as for the Googlebook's battery: a warning below 20%, an alert below 10%, whatever the rule's number is. */
    const val WARN_BELOW = 20
    const val ALERT_BELOW = 10
    /** A name is the device's own word about itself: it is shown as one line of at most this many characters. */
    const val NAME_MAX = 60

    // InputDevice's sources (a class in the low byte, the kind above it) and keyboard type, and BatteryState's statuses.
    private const val SOURCE_GAMEPAD = 0x401
    private const val SOURCE_STYLUS = 0x4002
    private const val SOURCE_JOYSTICK = 0x1000010
    private const val KEYBOARD_ALPHABETIC = 2
    private const val STATUS_CHARGING = 2
    private const val STATUS_FULL = 5

    enum class Kind(val glyph: String) { MOUSE(Sym.MOUSE), KEYBOARD(Sym.KEYBOARD), STYLUS(Sym.STYLUS), GAMEPAD(Sym.SPORTS_ESPORTS), HEADPHONES(Sym.HEADPHONES), WATCH(Sym.WATCH),
        /** A linked BentoBar's own device (items/Link): a phone or a tablet. */
        PHONE(Sym.SMARTPHONE), TABLET(Sym.TABLET_ANDROID) }

    /**
     * One connected Bluetooth device with a battery Android may know of: its address, name, the level it reports
     * (Android's -1: none), whether it is [main] (connected the classic way too, Android's DUAL type, not by
     * Bluetooth LE alone), and its Bluetooth class ([deviceClass], 0: unknown), which says what kind it is.
     */
    data class Audio(val address: String, val name: String, val level: Int, val main: Boolean = true, val deviceClass: Int = AUDIO_VIDEO,
                     val side: Ears? = null, val earbud: Boolean = false, val inUse: Boolean? = null, val charging: Boolean = false,
                     val chargingEars: Ears? = null)

    /**
     * One record of Samsung's companion-device battery list: its level, whether it is charging (or full on the charger),
     * and for earbuds their case's level, while Samsung knows it (an earbud in the case, or just out of it; else none).
     */
    data class Companion(val level: Int, val charging: Boolean, val case: Int? = null,
                         val leftCharging: Boolean = false, val rightCharging: Boolean = false, val caseCharging: Boolean = false) {
        /** Which earbuds charge in their case, as One UI marks them: both, the left one, the right one, or none. */
        val chargingEars: Ears? get() = when {
            leftCharging && rightCharging -> Ears.BOTH
            leftCharging -> Ears.LEFT
            rightCharging -> Ears.RIGHT
            else -> null
        }
    }

    /**
     * [RootTool]'s `batteries` lines as companions by address (upper case): `device`, address, type, level, status, left,
     * right, case; status 2 is charging and 5 full, as Android's battery statuses. A record without an address or a level
     * is left out; a case of 0 or less is one Samsung doesn't know.
     */
    fun companions(out: String): Map<String, Companion> = out.lineSequence().mapNotNull { line ->
        val f = line.trim().split('\t')
        if (f.size < 5 || f[0] != "device") return@mapNotNull null
        val address = f[1].trim().uppercase().takeIf { it.length == 17 } ?: return@mapNotNull null
        val level = f[3].trim().toIntOrNull()?.takeIf { it in 0..100 } ?: return@mapNotNull null
        val status = f[4].trim().toIntOrNull()
        fun on(i: Int) = f.getOrNull(i)?.trim()?.toIntOrNull().let { it == 2 || it == 5 }
        address to Companion(level, status == 2 || status == 5, f.getOrNull(7)?.trim()?.toIntOrNull()?.takeIf { it in 1..100 },
            leftCharging = on(8), rightCharging = on(9), caseCharging = on(10))
    }.toMap()

    /** An earbuds' case as last read: whose (the earbuds' name), its level, and when ([at]: wall-clock milliseconds). */
    data class Case(val name: String, val level: Int, val at: Long)

    /** The cases kept, newest first: [level] for [name] now replaces what was kept of it. A few at most. */
    fun remember(cases: List<Case>, name: String, level: Int, at: Long): List<Case> =
        (listOf(Case(name, level, at)) + cases.filter { it.name != name }).take(CASES_MAX)
    private const val CASES_MAX = 6

    /** The cases as kept on the device: a line each, level, time and name, separated by tabs. */
    fun encodeCases(cases: List<Case>): String = cases.joinToString("\n") { "${it.level}\t${it.at}\t${it.name.replace(Regex("[\\t\\r\\n]+"), " ")}" }

    fun decodeCases(text: String?): List<Case> = text.orEmpty().lineSequence().mapNotNull { l ->
        val f = l.split('\t', limit = 3)
        val level = f.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1..100 } ?: return@mapNotNull null
        val at = f.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        f.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { Case(it, level, at) }
    }.toList()

    /** A case read this recently is shown as it is now, without when. */
    const val CASE_FRESH_MS = 2 * 60_000L

    /**
     * Which earbuds of a pair are connected; on a device of one, which ear it is for (LE Audio's audio location), or
     * [ONE]: an earbud whose ear isn't known.
     */
    enum class Ears { LEFT, RIGHT, BOTH, ONE }

    /**
     * The glyph a device is drawn with: its kind's, but earbuds as they are connected. The earbud glyphs are the
     * headphones' with an invisible mark after it: the Material font draws headphones for each, One UI's icons
     * (util/OneUiIcons) both earbuds, the left one or the right one.
     */
    fun glyph(device: Device): String = when (device.ears) {
        Ears.BOTH -> EARBUDS_BOTH
        Ears.LEFT -> EARBUDS_LEFT
        Ears.RIGHT, Ears.ONE -> EARBUDS_RIGHT
        null -> device.kind.glyph
    }
    /**
     * A device on its charger, in the bar: a phone or a tablet as its outline with a bolt inside (One UI's icons, where the
     * build has them; else the bolt alone, `util/Ui.kt`), any other device as the bolt.
     */
    fun chargingGlyph(device: Device): String = when (device.kind) {
        Kind.PHONE, Kind.TABLET -> device.kind.glyph + if (device.speed == ChargeSpeed.SUPER_2) CHARGING_2 else CHARGING
        else -> device.speed?.glyph ?: Sym.BOLT
    }
    /** Marks a device's glyph as the charging one (an invisible character, as the earbuds' are marked). */
    const val CHARGING = "\u2060"
    /** The same, with the double bolt of super fast charging 2.0. */
    const val CHARGING_2 = CHARGING + ChargeSpeed.DOUBLE

    const val EARBUDS_BOTH = Sym.HEADPHONES + "\u200B"
    const val EARBUDS_LEFT = Sym.HEADPHONES + "\u200C"
    const val EARBUDS_RIGHT = Sym.HEADPHONES + "\u200D"

    /**
     * Which earbuds a pair's connected devices are: two or more, both; one that says its ear, that one; else
     * unknown (null): an earbud that doesn't say, or headphones that are one device.
     */
    fun ears(pair: List<Audio>): Ears? = when {
        pair.size >= 2 -> Ears.BOTH
        else -> pair.firstOrNull()?.let { it.side ?: if (it.earbud) Ears.ONE else null }
    }

    /**
     * The ear of each LE Audio device in `dumpsys bluetooth_manager` (root only): a `Device [xx:xx:xx:xx:ac:fb]` line,
     * then its `Audio Locations: Sink= right` line. Keyed by the address's last five characters, lower case, which is
     * what the report shows of it.
     */
    fun sides(dump: String): Map<String, Ears> {
        val out = HashMap<String, Ears>()
        var at: String? = null
        for (line in dump.lineSequence()) {
            Regex("""Device \[([0-9a-fA-F:x]{17})]""").find(line)?.let { at = it.groupValues[1].takeLast(5).lowercase() }
            val loc = Regex("""Audio Locations:\s*Sink=\s*(\w+)""").find(line)?.groupValues?.get(1)?.lowercase() ?: continue
            val side = when (loc) { "left" -> Ears.LEFT; "right" -> Ears.RIGHT; else -> null }
            at?.let { a -> if (side != null) out[a] = side }
        }
        return out
    }

    // Bluetooth's class of device: the major class, and the peripheral minor classes that say keyboard, pointer or gamepad.
    const val AUDIO_VIDEO = 0x400
    private const val PERIPHERAL = 0x500
    private const val WEARABLE = 0x700
    private const val MAJOR_MASK = 0x1F00

    /**
     * The kind a Bluetooth device is listed as, from its class: audio is headphones; a peripheral is a keyboard (with
     * or without a pointer), a gamepad or joystick, or else a mouse; a wearable is a watch. Null: none of these, and
     * not listed (a phone, a computer, a car).
     */
    fun kindOf(deviceClass: Int): Kind? = when (deviceClass and MAJOR_MASK) {
        AUDIO_VIDEO -> Kind.HEADPHONES
        WEARABLE -> Kind.WATCH
        PERIPHERAL -> when {
            deviceClass and 0x40 != 0 -> Kind.KEYBOARD
            deviceClass and 0x80 != 0 -> Kind.MOUSE
            deviceClass and 0x3C == 0x08 || deviceClass and 0x3C == 0x04 -> Kind.GAMEPAD
            deviceClass and 0x3C == 0x14 -> Kind.STYLUS
            else -> Kind.MOUSE
        }
        else -> null
    }

    /**
     * Connected Bluetooth headphones (and speakers) as devices to list: one for each pair, with a level. LE Audio
     * earbuds are two devices of one name, one for each earbud, and each reports a level. They don't mean the same:
     * on Galaxy Buds (read over GATT on a tablet) the left earbud's LE-only device said 13%, as Samsung's own app did,
     * and the main connection said 27%, the two earbuds added up. So a pair shows its lowest level: the earbud that
     * runs out first, and never a sum. One that reports no level (-1, or anything outside 0..100) is left out, as an
     * input device without a battery is.
     */
    /**
     * The devices of a pair that are in use. An earbud put in its case can keep its Bluetooth link and leave only LE
     * Audio ([Audio.inUse] false), so where LE Audio has some of a pair, the others aren't counted; where it has none
     * (headphones it doesn't carry, or LE Audio off), all are.
     */
    fun inUse(pair: List<Audio>): List<Audio> = if (pair.any { it.inUse == true }) pair.filter { it.inUse != false } else pair

    fun headphones(audio: List<Audio>): List<Device> =
        audio.groupBy { it.name.trim().ifEmpty { "\u0000" + it.address } }.values.mapNotNull { all ->
            val pair = inUse(all)
            val low = pair.filter { it.level in 0..100 }.minByOrNull { it.level } ?: return@mapNotNull null
            val first = pair.sortedByDescending { it.main }.first()
            val kind = kindOf(first.deviceClass) ?: return@mapNotNull null
            Device("bt:${first.address}", NowPlayingRules.oneLine(low.name, NAME_MAX), kind, low.level, charging = pair.any { it.charging },
                ears = if (kind == Kind.HEADPHONES) ears(pair) else null,
                chargingEars = if (kind == Kind.HEADPHONES) all.firstNotNullOfOrNull { it.chargingEars } else null)
        }

    /**
     * The input devices and the Bluetooth ones as one list, in the menu's order. A Bluetooth mouse or keyboard whose
     * battery Android's input devices already tell is listed once, from there: one whose name is the input device's or
     * begins it ("Pebble M350s" and "Pebble M350s Mouse") is the same device. Some devices tell only Bluetooth (a
     * Samsung tablet doesn't pass a Bluetooth LE mouse's battery to its input device): those come from Bluetooth.
     */
    fun merge(input: List<Device>, bluetooth: List<Device>): List<Device> {
        fun same(a: String, b: String) = a.isNotBlank() && b.isNotBlank() &&
            (a.startsWith(b, ignoreCase = true) || b.startsWith(a, ignoreCase = true))
        return order(input + bluetooth.filter { bt -> input.none { same(it.name, bt.name) } })
    }

    /** One input device as Android lists it, with what its battery says. A physical device can be several of these. */
    data class Seen(val descriptor: String, val name: String, val sources: Int, val keyboardType: Int,
                    val present: Boolean, val capacity: Float, val status: Int)

    /** One device with a battery. [percent]: null where it has a battery and doesn't say how full ("Level unknown"). [name] can be empty. */
    data class Device(val descriptor: String, val name: String, val kind: Kind, val percent: Int?, val charging: Boolean,
                      val ears: Ears? = null, val speed: ChargeSpeed? = null,
                      /** Earbuds only: which of them charge in their case (Samsung's list says so, with root). */
                      val chargingEars: Ears? = null)

    /** The sentences a screen reader hears, and the word for a device without a name: from the resources in the app, from the copy deck in the tests. */
    class Words(
        val none: () -> String,
        val level: (name: String, percent: Int) -> String,
        val low: (name: String, percent: Int) -> String,
        val charging: (name: String, percent: Int) -> String,
        val unknown: (name: String) -> String,
        val unnamed: () -> String,
    )

    /** What the item shows in the bar. */
    data class Bar(val glyph: String, val filled: Boolean, val text: String?, val tone: Tone, val active: Boolean, val desc: String, val tooltip: String?,
                   /** A charging device whose charger's speed is known (a linked one): its bolt is coloured as the Battery item's. */
                   val speed: ChargeSpeed? = null)

    private fun Int.has(source: Int) = this and source == source

    /**
     * The glyph a device gets: a stylus; a gamepad or joystick; a keyboard with letters, also when it
     * has a pointer; and a mouse for every pointer and for anything else. In that order, because a
     * controller has keys and a touchpad too, and a mouse reports its buttons as keys.
     */
    fun kind(sources: Int, keyboardType: Int): Kind = when {
        sources.has(SOURCE_STYLUS) -> Kind.STYLUS
        sources.has(SOURCE_GAMEPAD) || sources.has(SOURCE_JOYSTICK) -> Kind.GAMEPAD
        keyboardType == KEYBOARD_ALPHABETIC -> Kind.KEYBOARD
        else -> Kind.MOUSE
    }

    /** A battery's level (0 to 1) as a whole percentage, rounded: 0.29 is 28.999998 in a float. Null where it is no number. */
    fun percent(capacity: Float): Int? = if (!capacity.isFinite() || capacity < 0f) null else (capacity * 100).roundToInt().coerceAtMost(100)

    /** On its charger: charging, or full. No warning then, as for the Googlebook's own battery. */
    fun charging(status: Int): Boolean = status == STATUS_CHARGING || status == STATUS_FULL

    /**
     * The devices to list, in the menu's order: one for each descriptor that has a battery. A device
     * that shows up as keys and as a pointer is two input devices with one descriptor: it is one
     * device, with the shorter of the names ("Keychron K3", not "Keychron K3 Mouse") and whatever
     * either half knows of the battery. Should the halves disagree (Android gives two devices it
     * can't tell apart one descriptor too), the row is the one that matters more: the lowest that is
     * not charging, as in the bar. A device without a battery is left out: the built-in keyboard, a
     * wired mouse, a receiver that passes no level on.
     */
    fun devices(seen: List<Seen>): List<Device> {
        val byDescriptor = LinkedHashMap<String, MutableList<Seen>>()
        // Without a descriptor nothing says that two of them are one: each stands for itself.
        seen.forEachIndexed { i, s -> byDescriptor.getOrPut(s.descriptor.ifEmpty { "\u0000$i" }) { ArrayList() } += s }
        return order(byDescriptor.values.mapNotNull { parts ->
            val batteries = parts.filter { it.present }.map { percent(it.capacity) to charging(it.status) }
            if (batteries.isEmpty()) return@mapNotNull null
            val known = batteries.filter { it.first != null }
            val (percent, charging) = known.filter { !it.second }.minByOrNull { it.first ?: 0 } ?: known.minByOrNull { it.first ?: 0 }
                ?: (null to batteries.any { it.second })
            Device(
                descriptor = parts.first().descriptor,
                name = parts.map { NowPlayingRules.oneLine(it.name, NAME_MAX) }.filter { it.isNotEmpty() }.minByOrNull { it.length }.orEmpty(),
                kind = kind(parts.fold(0) { all, p -> all or p.sources }, parts.maxOf { it.keyboardType }),
                percent = percent,
                charging = charging,
            )
        })
    }

    /** The menu's order: the lowest level first; devices without a level last, by name. */
    fun order(devices: List<Device>): List<Device> =
        devices.sortedWith(compareBy<Device> { it.percent == null }.thenBy { it.percent ?: 0 }.thenBy { it.name.lowercase() }.thenBy { it.descriptor })

    /**
     * The device the bar shows: the lowest one that is not charging, so that a mouse charging at 8%
     * doesn't hide a keyboard dying at 12%; if all are charging, the lowest of them. A device that
     * doesn't say its level never counts. Null: there is none to show.
     */
    fun shown(devices: List<Device>, pinned: String?): Device? =
        pinned?.let { name -> devices.firstOrNull { it.name == name } } ?: shown(devices)

    /** The item's option: the name of the device the user keeps in the bar; none, the lowest is shown. */
    const val PINNED = "show"

    fun shown(devices: List<Device>): Device? {
        val known = devices.filter { it.percent != null }
        return known.filter { !it.charging }.minByOrNull { it.percent ?: 0 } ?: known.minByOrNull { it.percent ?: 0 }
    }

    /** Low: under 20% and not charging. The menu's row is drawn in the error color then, and the sentence says "low". */
    fun low(device: Device): Boolean = device.percent != null && device.percent < WARN_BELOW && !device.charging

    /** The device's name as it is shown; a device that has none is called by the word for that. */
    fun name(device: Device, words: Words): String = device.name.ifEmpty { words.unnamed() }

    /** A level as the bar and the menu write it: "85%". */
    fun percentText(percent: Int): String = "$percent%"

    /** One device as a sentence: the bar's spoken description, and its row's in the menu. */
    fun desc(device: Device, words: Words): String {
        val name = name(device, words)
        val percent = device.percent ?: return words.unknown(name)
        return when {
            device.charging -> words.charging(name, percent)
            percent < WARN_BELOW -> words.low(name, percent)
            else -> words.level(name, percent)
        }
    }

    /**
     * The item in the bar, for [devices] in the menu's order. It comes out while the device shown is
     * below [lowPct] (the rule's number) and not charging; the tones keep their own 20% and 10%, so
     * with the rule at 50% a device at 35% comes out in the normal tone. With no level to show it is
     * the outlined mouse without text, and says what is known: that no device reports a battery, or
     * that the one there is doesn't say its level.
     */
    fun bar(devices: List<Device>, lowPct: Int, words: Words, pinned: String? = null): Bar {
        // The device the user picked for the bar, while it is there; else the lowest.
        val device = shown(devices, pinned)
        val percent = device?.percent ?: return Bar(Sym.MOUSE, filled = false, text = null, tone = Tone.NORMAL, active = false,
            desc = devices.firstOrNull()?.let { desc(it, words) } ?: words.none(), tooltip = null)
        val tone = when {
            device.charging -> Tone.NORMAL
            percent < ALERT_BELOW -> Tone.ALERT
            percent < WARN_BELOW -> Tone.WARN
            else -> Tone.NORMAL
        }
        // Charging: the bolt, as the Battery item shows its own; the tooltip says which device.
        return Bar(if (device.charging) chargingGlyph(device) else glyph(device), filled = true, text = percentText(percent), tone = tone, active = !device.charging && percent < lowPct,
            desc = desc(device, words), tooltip = name(device, words), speed = device.speed?.takeIf { device.charging })
    }

    /**
     * Whether a reading of this age is too old to show again when the bar comes back: more than two
     * of the minute's readings old. A short absence keeps the list (no blink in the bar while it is
     * read again); after a long one a device may be long gone.
     */
    fun tooOld(ageMs: Long?): Boolean = ageMs != null && ageMs > 2 * EVERY_MS

    private val STAGED_KINDS = mapOf("mouse" to Kind.MOUSE, "keyboard" to Kind.KEYBOARD, "stylus" to Kind.STYLUS, "gamepad" to Kind.GAMEPAD,
        "controller" to Kind.GAMEPAD, "headphones" to Kind.HEADPHONES, "phone" to Kind.PHONE, "tablet" to Kind.TABLET)
    private val STAGED_NAMES = mapOf(Kind.MOUSE to "Mouse", Kind.KEYBOARD to "Keyboard", Kind.STYLUS to "Stylus", Kind.GAMEPAD to "Controller",
        Kind.HEADPHONES to "Headphones", Kind.PHONE to "Phone", Kind.TABLET to "Tablet")

    /**
     * The test hook's devices, from its words: `mouse=15 keyboard=85c stylus=?` (c: charging; ?: a
     * battery without a level, also written `unknown`, which no shell takes for a pattern), with a
     * name of its own after a colon where one is wanted, underscores for its spaces
     * (`mouse:MX_Master_3S=8`). `none`: no device at all. Null: not understood.
     */
    fun staged(tokens: List<String>): List<Device>? {
        if (tokens == listOf("none")) return emptyList()
        if (tokens.isEmpty()) return null
        return order(tokens.mapIndexed { i, token ->
            val what = token.substringBefore('=', "")
            val level = token.substringAfter('=', "")
            val kind = STAGED_KINDS[what.substringBefore(':').lowercase()] ?: return null
            val percent = when (val number = level.removeSuffix("c")) {
                "?", "unknown" -> null
                else -> number.toIntOrNull()?.takeIf { it in 0..100 } ?: return null
            }
            val name = NowPlayingRules.oneLine(what.substringAfter(':', "").replace('_', ' '), NAME_MAX)
            Device("staged:$i", name.ifEmpty { STAGED_NAMES.getValue(kind) }, kind, percent, charging = level.endsWith('c'))
        })
    }
}

/**
 * When the devices are read besides once a minute: at once when Android says that one was connected,
 * changed or removed, and once more a moment later, when a device that has just connected knows its
 * level. [read] asks for a reading and says whether it was taken; one that was held back (the loader
 * takes no second one within a second) is asked for again on the next [tick]. Pure: the item gives
 * it the loader and the clock.
 */
class DevicesWatch(private val read: () -> Boolean) {
    private var pending = false
    private var changedAt = NEVER

    /** Android reported a device connected, changed or removed. */
    fun changed(now: Long) {
        changedAt = now
        pending = !read()
    }

    /** Once a second while an item is live. */
    fun tick(now: Long) {
        if (pending) pending = !read()
        else if (changedAt != NEVER && now - changedAt !in 0 until DevicesRules.SETTLE_MS) { changedAt = NEVER; pending = !read() }
    }

    /** No item is live any more: nothing is left to read. */
    fun clear() { pending = false; changedAt = NEVER }

    private companion object { const val NEVER = Long.MIN_VALUE }
}
