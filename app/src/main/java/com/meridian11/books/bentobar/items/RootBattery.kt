package com.meridian11.books.bentobar.items

import android.os.SystemClock
import java.util.concurrent.TimeUnit

/**
 * What a Samsung device knows of its battery and keeps from apps: the real charge-cycle count and the battery's health
 * in percent, in its power-supply files, which only root may read (Android's own count reads 0 there, and its health
 * percentage needs a system permission). Read with `su` on a rooted device, only while the Battery menu is open; where
 * root is refused or absent, nothing is shown and it isn't asked again until BentoBar restarts.
 */
object RootBattery {
    data class Info(val cycles: Int?, val healthPct: Int?)

    @Volatile var info: Info? = null; private set
    @Volatile private var refused = false
    @Volatile private var running = false
    @Volatile private var readAt = 0L

    /** The menu is open: read the files, unless that was done in the last ten minutes, is under way or was refused. */
    fun want() {
        if (refused || running) return
        if (info != null && SystemClock.elapsedRealtime() - readAt < EVERY_MS) return
        running = true
        Thread({ read() }, "BentoBar root battery").start()
    }

    private fun read() {
        try {
            val p = ProcessBuilder("su", "-c", "cd $DIR && for f in $FILES; do [ -r \$f ] && echo \$f=\$(cat \$f); done")
                .redirectErrorStream(true).start()
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroy(); refused = true; return }
            val parsed = parse(p.inputStream.bufferedReader().readText())
            if (p.exitValue() != 0 || parsed == null) refused = true
            else { info = parsed; readAt = SystemClock.elapsedRealtime() }
        } catch (e: Exception) {
            refused = true // no su on this device, or it can't start
        } finally {
            running = false
        }
    }

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
