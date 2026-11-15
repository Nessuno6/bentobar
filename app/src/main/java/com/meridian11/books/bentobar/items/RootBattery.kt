package com.meridian11.books.bentobar.items

/**
 * What a Samsung device knows of its battery and keeps from apps: the real charge-cycle count and the battery's health
 * in percent, in its power-supply files, which Android keeps from ordinary apps (Android's own count reads 0 there, and
 * its health percentage needs a system permission). Read via Shizuku or `su` only while the Battery menu is open; where
 * neither is available or allowed, nothing is shown until BentoBar restarts or Shizuku is granted.
 */
object RootBattery {
    data class Info(val cycles: Int?, val healthPct: Int?)

    private val reading = PrivReading(
        "cd $DIR && for f in $FILES; do [ -r \$f ] && echo \$f=\$(cat \$f); done",
        EVERY_MS,
        timeoutMs = 5_000,
    ) { parse(it) }

    val info: Info? get() = reading.value

    /** The menu is open: read the files, unless that was done in the last ten minutes, is under way or failed for this epoch. */
    fun want() = reading.want()

    /**
     * The files' `name=value` lines as what they say: `battery_cycle`, Samsung's count of full charge cycles, and
     * `fg_asoc`, its fuel gauge's capacity against the battery's design, in percent. Values out of their range are
     * left out; nothing usable is null.
     */
    fun parse(out: String): Info? {
        val values = out.lineSequence().mapNotNull { line ->
            val (k, v) = line.trim().split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            v.trim().toIntOrNull()?.let { k.trim() to it }
        }.toMap()
        val cycles = values["battery_cycle"]?.takeIf { it >= 0 }
        val health = values["fg_asoc"]?.takeIf { it in 1..100 }
        return if (cycles == null && health == null) null else Info(cycles, health)
    }

    private const val DIR = "/sys/class/power_supply/battery"
    private const val FILES = "battery_cycle fg_asoc"
    private const val EVERY_MS = 10 * 60_000L
}
