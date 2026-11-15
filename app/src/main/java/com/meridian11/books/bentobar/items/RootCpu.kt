package com.meridian11.books.bentobar.items

import android.content.pm.PackageManager

/**
 * Which apps use the CPU, via Shizuku or `su`: Android doesn't let an app see other apps' processes, but `top` run with
 * elevated access does. Read only while the CPU menu is open, at most every [EVERY_MS]; where neither Shizuku nor root
 * is granted, nothing is shown until Shizuku is granted or BentoBar restarts. No process or app name is logged.
 */
object RootCpu {
    /**
     * One app or system process, its share of the whole processor (0 to 1, all cores together), and whether it is the
     * [system]'s: a process that is no app, or an app without a launcher icon (a service, the system UI).
     */
    data class User(val name: String, val share: Double, val system: Boolean = true)

    /** How many of each kind the menu lists. */
    const val EACH = 5

    private val reading = PrivReading(
        // Two frames a second apart: the second is the load over that second (the first is since each process began).
        "top -b -n 2 -d 1 -s 1 -m 40 -o %CPU,NAME",
        EVERY_MS,
        timeoutMs = 6_000,
        keepEmpty = true,
    ) { out ->
        if (!out.contains("%CPU") || !out.contains("NAME")) return@PrivReading null
        val cores = Runtime.getRuntime().availableProcessors()
        val pm = Env.app.packageManager
        parse(out, cores, TOP).map { u ->
            appOrProcess(pm, u.name).let { (n, sys) -> u.copy(name = n, system = sys) }
        }
    }

    val users: List<User>? get() = reading.value

    /** The menu is open: read again, unless a reading is under way, was taken within [EVERY_MS] or failed for this epoch. */
    fun want() = reading.want()

    /**
     * The busiest of `top`'s last frame (`%CPU NAME` rows under a header), with an app's processes together (its
     * ":service" ones under the package's name), as shares of all [cores]: top counts a whole core as 100%. Only the
     * [limit] busiest with any load, busiest first.
     */
    fun parse(out: String, cores: Int, limit: Int): List<User> {
        val lines = out.lines()
        val header = lines.indexOfLast { it.contains("%CPU") && it.contains("NAME") }
        if (header < 0 || cores <= 0) return emptyList()
        val byName = LinkedHashMap<String, Double>()
        for (line in lines.drop(header + 1)) {
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) continue
            val cpu = parts[0].toDoubleOrNull() ?: continue
            val name = parts[1].trim().substringBefore(':')
            if (name.isEmpty() || name == "top") continue
            byName[name] = (byName[name] ?: 0.0) + cpu
        }
        return byName.filterValues { it > 0.0 }.map { (n, cpu) -> User(n, (cpu / 100.0 / cores).coerceAtMost(1.0)) }
            .sortedByDescending { it.share }.take(limit)
    }

    private const val EVERY_MS = 3_000L
    private const val TOP = 30
}
