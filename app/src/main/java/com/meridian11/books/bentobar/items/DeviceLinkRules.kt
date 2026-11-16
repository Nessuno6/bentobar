package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.DevicesRules.Device
import com.meridian11.books.bentobar.items.DevicesRules.Ears
import com.meridian11.books.bentobar.items.DevicesRules.Kind

/**
 * What two linked BentoBars tell each other ([Link]): each its own battery and the devices it shows in Device batteries
 * (a watch, earbuds, a mouse), as a few lines of text. Pure, unit-tested ([DeviceLinkRulesTest]).
 *
 *     bentobar-link 1
 *     self  TABLET  74  1  -     Tab S12 Ultra
 *     dev   WATCH   50  0  -     Galaxy Watch7
 *     dev   HEADPHONES 13 0 BOTH Galaxy Buds4 Pro
 *
 * Fields are separated by tabs: kind, level (-1: none), charging (0: not; 1 to 4: on a charger, how fast as
 * [ChargeSpeed], normal to super fast 2.0; a side that doesn't know the speed says 1), the ears (earbuds only, else -), and the name
 * last. A line that can't be read is left out; a message without its first line is none.
 */
object DeviceLinkRules {
    const val HEADER = "bentobar-link 1"

    /** A device's battery as one side says it. [level] null: the device has a battery without a level. */
    data class Battery(val kind: Kind, val name: String, val level: Int?, val charging: Boolean, val ears: Ears? = null,
                       val speed: ChargeSpeed? = null)

    /** What one side says: its own device and the devices it shows. */
    data class Status(val self: Battery, val devices: List<Battery>)

    /** A message is sent this often while linked, and at once on linking. */
    const val EVERY_MS = 30_000L
    /** A status older than this is not shown: the other side stopped sending (two missed messages and some). */
    const val STALE_MS = 3 * EVERY_MS
    /** How long an outbound or incoming link waits to settle before giving up so the menu does not stay stuck. */
    const val CONNECT_TIMEOUT_MS = 20_000L

    /**
     * Whether Apple's peer-to-peer Wi-Fi (AWDL) is up, from the names of the network interfaces that are up: Quick Share
     * to an iPhone, iPad or Mac brings one up (`awdl0`, with its low-latency companion `llw0`) for as long as it sends.
     * The link holds the same radio, and Quick Share stalled beside it, so the link gives way while AWDL is up.
     */
    fun awdl(upInterfaces: Collection<String>): Boolean =
        upInterfaces.any { n -> AWDL_PREFIXES.any { n.startsWith(it, ignoreCase = true) } }
    private val AWDL_PREFIXES = listOf("awdl", "llw")

    /**
     * How often the link looks for AWDL, the share sheet and Quick Share while it is open, opening, or waiting for them
     * to end: the share sheet comes about a second before Quick Share, which decides when it starts whether it can reach
     * Apple devices, so the link has to be gone by then.
     */
    const val AWDL_EVERY_MS = 1_000L

    /**
     * Quick Share (Samsung's): while it is in front the link gives way at once. Its Apple mode (AirDrop) drops the Wi-Fi
     * network and takes the radio for AWDL, and with the link holding on it found no Apple device; waiting for AWDL
     * to come up was waiting for something the link kept from happening.
     */
    val QUICK_SHARE = setOf(
        "com.samsung.android.app.sharelive",
        // The system's share sheet, which comes first: Quick Share checks for AWDL as it starts, too late to give way then.
        "com.android.intentresolver",
    )

    /** After Quick Share left the front and AWDL went down, the link waits this long: a send can go on in the background. */
    const val LINGER_MS = 60_000L

    /** While not linked, Device batteries looks this often for the device last linked, to link back to it (`Link.follow`). */
    const val FOLLOW_MS = 120_000L

    /** Whether the link gives way now: AWDL is up, or Quick Share is in front (null: no Usage access to tell). */
    fun givesWay(awdl: Boolean, quickShare: Boolean?): Boolean = awdl || quickShare == true

    /** Whether a link that gave way opens again: nothing needs the radio, and has not for [LINGER_MS]. */
    fun relinks(busy: Boolean, sinceBusyMs: Long): Boolean = !busy && sinceBusyMs >= LINGER_MS

    /** A name as one field: no tabs or line breaks, and not too long. */
    private fun field(name: String) = name.replace(Regex("[\\t\\r\\n]+"), " ").trim().take(NAME_MAX)
    private const val NAME_MAX = 60

    private fun line(tag: String, b: Battery) =
        listOf(tag, b.kind.name, (b.level ?: -1).toString(), charging(b), b.ears?.name ?: "-", field(b.name)).joinToString("\t")

    private fun charging(b: Battery) = if (!b.charging) "0" else ((b.speed?.ordinal ?: 0) + 1).toString()

    fun encode(status: Status): String =
        (listOf(HEADER, line("self", status.self)) + status.devices.map { line("dev", it) }).joinToString("\n")

    fun decode(text: String): Status? {
        val lines = text.lineSequence().map { it.trimEnd('\r') }.toList()
        if (lines.firstOrNull() != HEADER) return null
        var self: Battery? = null
        val devices = ArrayList<Battery>()
        for (l in lines.drop(1)) {
            val f = l.split('\t')
            if (f.size < 6) continue
            val kind = runCatching { Kind.valueOf(f[1]) }.getOrNull() ?: continue
            val level = f[2].toIntOrNull()?.takeIf { it in -1..100 } ?: continue
            val ears = if (f[4] == "-") null else runCatching { Ears.valueOf(f[4]) }.getOrNull()
            val charge = f[3].toIntOrNull() ?: 0
            // Only a side's own device says how fast it charges: a watch on its cradle has no speed to tell.
            val speed = if (charge >= 1 && f[0] == "self") ChargeSpeed.entries[(charge - 1).coerceAtMost(ChargeSpeed.entries.size - 1)] else null
            val b = Battery(kind, field(f.drop(5).joinToString(" ")), level.takeIf { it >= 0 }, charge >= 1, ears, speed)
            when (f[0]) {
                "self" -> if (self == null) self = b
                "dev" -> devices += b
            }
        }
        return self?.let { Status(it, devices) }
    }

    /** What this side sends of its own devices: those it found itself, never those another side told it of. */
    fun outgoing(devices: List<Device>): List<Battery> = devices.filter { !it.descriptor.startsWith(PREFIX) }
        .map { Battery(it.kind, it.name, it.percent, it.charging, it.ears, it.speed) }

    /**
     * The other side's devices as rows of Device batteries: its own device first, then those it shows that this side
     * doesn't show itself (a watch both see is listed once, as this side reads it).
     */
    fun incoming(status: Status, own: List<Device>): List<Device> {
        fun same(a: String, b: String) = a.isNotBlank() && b.isNotBlank() &&
            (a.startsWith(b, ignoreCase = true) || b.startsWith(a, ignoreCase = true))
        val theirs = status.devices.filter { d -> own.none { same(it.name, d.name) } }
        return (listOf(status.self) + theirs).mapIndexed { i, b ->
            Device("$PREFIX$i", b.name, b.kind, b.level, b.charging, b.ears, b.speed)
        }
    }

    /** The descriptor of every device another side told of. */
    const val PREFIX = "link:"
}
