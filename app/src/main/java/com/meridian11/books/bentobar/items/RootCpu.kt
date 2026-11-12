package com.meridian11.books.bentobar.items

import android.content.pm.PackageManager
import android.os.SystemClock
import java.util.concurrent.TimeUnit

/**
 * Which apps use the CPU, on a rooted device: Android doesn't let an app see other apps' processes, but `top` run as
 * root does. Read only while the CPU menu is open, at most every [EVERY_MS]; where root is refused or absent, nothing
 * is shown and it isn't asked again until BentoBar restarts. No process or app name is logged.
 */
object RootCpu {
    /**
     * One app or system process, its share of the whole processor (0 to 1, all cores together), and whether it is the
     * [system]'s: a process that is no app, or an app without a launcher icon (a service, the system UI).
     */
    data class User(val name: String, val share: Double, val system: Boolean = true)


    /** How many of each kind the menu lists. */
    const val EACH = 5

    @Volatile var users: List<User>? = null; private set
    @Volatile private var refused = false
    @Volatile private var running = false
    @Volatile private var readAt = 0L

    /** The menu is open: read again, unless a reading is under way, was taken within [EVERY_MS] or root was refused. */
    fun want() {
        if (refused || running) return
        if (SystemClock.elapsedRealtime() - readAt < EVERY_MS) return
        running = true
        Thread({ read() }, "BentoBar root cpu").start()
    }

    private fun read() {
        try {
            // Two frames a second apart: the second is the load over that second (the first is since each process began).
            val p = ProcessBuilder("su", "-c", "top -b -n 2 -d 1 -s 1 -m 40 -o %CPU,NAME").redirectErrorStream(true).start()
            if (!p.waitFor(6, TimeUnit.SECONDS)) { p.destroy(); refused = true; return }
            val out = p.inputStream.bufferedReader().readText()
            if (p.exitValue() != 0) { refused = true; return }
            val cores = Runtime.getRuntime().availableProcessors()
            val pm = Env.app.packageManager
            users = parse(out, cores, TOP).map { u ->
                val app = runCatching { pm.getApplicationInfo(u.name, 0) }.getOrNull()
                u.copy(name = label(pm, u.name), system = app == null || pm.getLaunchIntentForPackage(u.name) == null)
            }
            readAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            refused = true // no su on this device, or it can't start
        } finally {
            running = false
        }
    }

    /** An app's name for a package ("com.google.android.youtube" is YouTube); a system process keeps its own. */
    private fun label(pm: PackageManager, name: String): String = runCatching {
        pm.getApplicationInfo(name, 0).loadLabel(pm).toString()
    }.getOrDefault(name)

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
