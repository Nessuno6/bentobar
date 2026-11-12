package com.meridian11.books.bentobar.items

import android.content.pm.PackageManager
import android.os.SystemClock
import java.util.concurrent.TimeUnit

/**
 * One command run with `su` on a background thread, at most every [everyMs] while something wants it, its output turned
 * into [T] by [parse]. Where root is refused or absent, nothing is shown and it isn't asked again until BentoBar
 * restarts. Nothing it reads is logged.
 */
class RootReading<T>(private val command: String, private val everyMs: Long, private val parse: (String) -> T?) {
    @Volatile var value: T? = null; private set
    @Volatile private var refused = false
    @Volatile private var running = false
    @Volatile private var readAt = 0L

    fun want() {
        if (refused || running) return
        if (SystemClock.elapsedRealtime() - readAt < everyMs) return
        running = true
        Thread({ read() }, "BentoBar root").start()
    }

    private fun read() {
        try {
            val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            if (!p.waitFor(6, TimeUnit.SECONDS)) { p.destroy(); refused = true; return }
            val out = p.inputStream.bufferedReader().readText()
            val parsed = if (p.exitValue() == 0) parse(out) else null
            if (parsed == null) refused = true else { value = parsed; readAt = SystemClock.elapsedRealtime() }
        } catch (e: Exception) {
            refused = true
        } finally {
            running = false
        }
    }
}

/**
 * What the CPU and Memory menus list with root (their item's "Apps in the menu"): nothing, your apps only, or your apps
 * and the system's, under the item's option "apps". A layout from before the choice ("false") is [OFF].
 */
enum class AppsShown(val key: String) {
    OFF("off"), APPS("apps"), ALL("all");

    /** Whether the system's processes are listed too. */
    val system get() = this == ALL

    companion object {
        const val OPTION = "apps"
        fun of(item: com.meridian11.books.bentobar.data.ItemConfig): AppsShown = when (item.opt(OPTION, ALL.key)) {
            "false", OFF.key -> OFF
            APPS.key -> APPS
            else -> ALL
        }
    }
}

/** An app's or process's name as the menus show it, and whether it is the system's (no app, or an app without a launcher icon). */
internal fun appOrProcess(pm: PackageManager, name: String): Pair<String, Boolean> {
    val app = runCatching { pm.getApplicationInfo(name, 0) }.getOrNull() ?: return name to true
    return app.loadLabel(pm).toString() to (pm.getLaunchIntentForPackage(name) == null)
}

/** Which apps hold the most memory, on a rooted device: resident memory per process from `top` run as root. */
object RootMem {
    /** One app or process, the memory it holds in bytes, and whether it is the system's. */
    data class User(val name: String, val bytes: Long, val system: Boolean = true)


    private val reading = RootReading("top -b -n 1 -s 1 -m 40 -o RES,NAME", 5_000L) { out ->
        val pm = Env.app.packageManager
        parse(out, 30).takeIf { it.isNotEmpty() }?.map { u -> appOrProcess(pm, u.name).let { (n, sys) -> u.copy(name = n, system = sys) } }
    }
    val users get() = reading.value
    fun want() = reading.want()

    /**
     * `top`'s `RES NAME` rows (sizes like 587M, 1.2G, 900K) as users, an app's processes together under its package's
     * name, the [limit] biggest first.
     */
    fun parse(out: String, limit: Int): List<User> {
        val lines = out.lines()
        val header = lines.indexOfLast { it.contains("RES") && it.contains("NAME") }
        if (header < 0) return emptyList()
        val byName = LinkedHashMap<String, Long>()
        for (line in lines.drop(header + 1)) {
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) continue
            val bytes = size(parts[0]) ?: continue
            val name = parts[1].trim().substringBefore(':')
            if (name.isEmpty() || name == "top") continue
            byName[name] = (byName[name] ?: 0L) + bytes
        }
        return byName.filterValues { it > 0 }.map { (n, b) -> User(n, b) }.sortedByDescending { it.bytes }.take(limit)
    }

    /** A size as `top` writes it, in bytes: a number with K, M, G or T after it (kibibytes without). */
    fun size(text: String): Long? {
        val unit = text.lastOrNull()?.uppercaseChar() ?: return null
        val (number, factor) = when (unit) {
            'K' -> Pair(text.dropLast(1), 1L shl 10)
            'M' -> Pair(text.dropLast(1), 1L shl 20)
            'G' -> Pair(text.dropLast(1), 1L shl 30)
            'T' -> Pair(text.dropLast(1), 1L shl 40)
            else -> Pair(text, 1L shl 10)
        }
        return number.toDoubleOrNull()?.let { (it * factor).toLong() }
    }
}

/** The chip's own temperatures, on a rooted device: its thermal zones, which only root may read. */
object RootThermal {
    /** In °C: the hottest CPU core, the GPU, and the chip's hottest point; null where the device has no such zone. */
    data class Temps(val cpu: Double?, val gpu: Double?, val soc: Double?)

    private val reading = RootReading(
        "for z in /sys/class/thermal/thermal_zone*; do echo \$(cat \$z/type)=\$(cat \$z/temp); done 2>/dev/null", 3_000L,
    ) { parse(it) }
    val temps get() = reading.value
    fun want() = reading.want()

    /**
     * `type=temp` lines (milli°C, as Linux writes them) as temperatures: CPU the hottest zone named cpu…, GPU the
     * hottest gpu…, the chip soc_max or else the hottest of all. Readings outside 0–150 °C (an unused sensor) are left out.
     */
    fun parse(out: String): Temps? {
        val zones = out.lines().mapNotNull { line ->
            val (type, raw) = line.trim().split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val v = raw.trim().toDoubleOrNull() ?: return@mapNotNull null
            val c = if (v > 1000) v / 1000 else v
            if (c in 0.0..150.0) type.trim().lowercase() to c else null
        }
        if (zones.isEmpty()) return null
        val cpu = zones.filter { it.first.startsWith("cpu") }.maxOfOrNull { it.second }
        val gpu = zones.filter { it.first.startsWith("gpu") }.maxOfOrNull { it.second }
        val soc = zones.firstOrNull { it.first == "soc_max" }?.second ?: zones.maxOf { it.second }
        return Temps(cpu, gpu, soc)
    }
}

/**
 * What only root can say of Bluetooth devices, read on the Devices loader's background thread: which ear each LE Audio
 * earbud is for (Android keeps that for system apps; its Bluetooth report has it), and a Galaxy watch's battery, which
 * the watch tells only Samsung's Galaxy Wearable plugin (kept in that plugin's own records). Each at most every
 * [EVERY_MS]; once root is refused or absent, never again until BentoBar restarts. No address or name is logged.
 */
object RootBt {
    private const val EVERY_MS = 60_000L
    /** A watch level older than this is no longer the watch's. */
    private const val WATCH_FRESH_MS = 3 * 60 * 60_000L
    @Volatile private var refused = false
    private var sidesAt = 0L
    private var sides: Map<String, DevicesRules.Ears> = emptyMap()
    private var watchesAt = 0L
    private var watches: Map<String, Pair<Int, Long>> = emptyMap()

    /** [command] as root, its output; null where root is refused or it failed. Blocks: background threads only. */
    private fun run(command: String): String? {
        if (refused) return null
        // In the system's own mount namespace: started from an app, su sees the app's, where other apps' data folders
        // (the watch plugin's records) are hidden. Where su has no such option, as it is.
        return attempt(listOf("su", "--mount-master", "-c", command)) ?: attempt(listOf("su", "-c", command)).also { if (it == null) refused = true }
    }

    /** [argv] with "; true" after its command (one that finds nothing, grep without a match, isn't a failure); null on failure. */
    private fun attempt(argv: List<String>): String? = try {
        val p = ProcessBuilder(argv.dropLast(1) + (argv.last() + "; true")).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(8, TimeUnit.SECONDS)) { p.destroy(); null } else if (p.exitValue() != 0) null else out
    } catch (e: Exception) { null }

    /** A Bluetooth device came or went: the ears and the companion list are read again on the next look. */
    @Synchronized fun forgetSides() { sidesAt = 0L; companionsAt = 0L }

    private var companionsAt = 0L
    private var companions: Map<String, DevicesRules.Companion> = emptyMap()
    /** The companion list is read this often at most: it is live, and a read starts a process. */
    private const val COMPANIONS_MS = 30_000L

    /**
     * Samsung's companion-device battery list (what its battery widget shows), read by BentoBar's own [RootTool] run as
     * root: only the system may ask for it, and root counts as the system. The record of [address], if any.
     */
    @Synchronized fun companion(address: String): DevicesRules.Companion? {
        val now = SystemClock.elapsedRealtime()
        if (now - companionsAt > COMPANIONS_MS) {
            companionsAt = now
            val apk = Env.app.applicationInfo.sourceDir
            run("CLASSPATH=$apk app_process /system/bin ${RootTool::class.java.name} batteries")?.let { companions = DevicesRules.companions(it) }
        }
        return companions[address.uppercase()]
    }

    @Synchronized fun side(address: String): DevicesRules.Ears? {
        val now = SystemClock.elapsedRealtime()
        if (now - sidesAt > EVERY_MS) {
            sidesAt = now
            run("dumpsys bluetooth_manager | grep -E 'Device \\[|Audio Locations'")?.let { sides = DevicesRules.sides(it) }
        }
        return sides[address.takeLast(5).lowercase()]
    }

    @Synchronized fun watchLevel(address: String): Int? {
        val now = SystemClock.elapsedRealtime()
        if (now - watchesAt > EVERY_MS) {
            watchesAt = now
            readWatches()?.let { watches = it }
        }
        val (level, at) = watches[address.uppercase()] ?: return null
        return level.takeIf { System.currentTimeMillis() - at < WATCH_FRESH_MS }
    }

    /**
     * The Galaxy Wearable plugins' battery records (com.samsung.wearable.watch…plugin, one per watch model): their
     * database and its write-ahead log, copied out as base64 and read here, the latest level of each watch's address.
     */
    private fun readWatches(): Map<String, Pair<Int, Long>>? {
        val out = run("for d in /data/data/com.samsung.wearable.watch*plugin/databases; do [ -f \$d/battery_db ] || continue; " +
            "echo DB; base64 -w 0 \$d/battery_db; echo; echo WAL; [ -f \$d/battery_db-wal ] && base64 -w 0 \$d/battery_db-wal; echo; done") ?: return null
        val all = HashMap<String, Pair<Int, Long>>()
        val lines = out.lines()
        var i = 0
        var n = 0
        while (i < lines.size) {
            if (lines[i].trim() == "DB") {
                val db = lines.getOrNull(i + 1).orEmpty().trim()
                val wal = if (lines.getOrNull(i + 2)?.trim() == "WAL") lines.getOrNull(i + 3).orEmpty().trim() else ""
                runCatching { all.putAll(levels(db, wal, n++)) }
                i += 4
            } else i++
        }
        return all
    }

    private fun levels(db: String, wal: String, n: Int): Map<String, Pair<Int, Long>> {
        val dir = java.io.File(Env.app.cacheDir, "watchdb$n").apply { mkdirs() }
        val file = java.io.File(dir, "battery_db")
        java.io.File(dir, "battery_db-shm").delete()
        file.writeBytes(android.util.Base64.decode(db, android.util.Base64.DEFAULT))
        java.io.File(dir, "battery_db-wal").let { if (wal.isNotEmpty()) it.writeBytes(android.util.Base64.decode(wal, android.util.Base64.DEFAULT)) else it.delete() }
        val out = HashMap<String, Pair<Int, Long>>()
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { sql ->
            sql.rawQuery("SELECT _btAddress, Value, Time FROM Battery_tb", null).use { c ->
                while (c.moveToNext()) {
                    val address = c.getString(0)?.uppercase().orEmpty()
                    val level = c.getString(1)?.toIntOrNull() ?: continue
                    val at = c.getString(2)?.toLongOrNull() ?: continue
                    if (address.isEmpty() || level !in 0..100) continue
                    if ((out[address]?.second ?: 0L) < at) out[address] = level to at
                }
            }
        }
        dir.deleteRecursively()
        return out
    }

    fun debugSides() = sides.values.joinToString(",") { it.name.lowercase() }
    fun debugWatches() = watches.values.joinToString(",") { "${it.first}%" }
}
