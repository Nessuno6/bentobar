package com.meridian11.books.bentobar.items

import android.content.Context
import android.os.Process
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * BentoBar's UserService run by Shizuku under shell (`uid=2000`) or root (`uid=0`) privileges. Runs in a separate
 * Shizuku-managed process; never logs what it reads and never modifies system state.
 */
class ShizukuService(private val context: Context?) : IPrivilegedService.Stub() {
    @Suppress("unused")
    constructor() : this(null)

    override fun uid(): Int = Process.myUid()

    override fun run(command: String, timeoutMs: Int): String {
        val result = execProcess(listOf("sh", "-c", command), timeoutMs.toLong().coerceIn(500L, 15_000L))
            ?: return PrivRules.encode(-1, "")
        return PrivRules.encode(result.exitCode, result.output)
    }

    override fun batteries(): String = runCatching {
        RootTool.readBatteries(context)
    }.getOrElse { e -> "error\t${(e.cause ?: e).javaClass.simpleName}\n" }

    override fun destroy() {
        exitProcess(0)
    }
}

/** Runs [argv] on the calling thread, draining output concurrently so large outputs can't stall [waitFor]. */
internal fun execProcess(argv: List<String>, timeoutMs: Long): PrivRules.Result? {
    var process: java.lang.Process? = null
    return try {
        val p = ProcessBuilder(argv).redirectErrorStream(true).start()
        process = p
        val out = StringBuilder()
        val reader = Thread({
            runCatching {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        out.append(buf, 0, n)
                    }
                }
            }
        }, "BentoBar proc read").apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            reader.join(200)
            return null
        }
        reader.join(500)
        PrivRules.Result(p.exitValue(), out.toString())
    } catch (_: Exception) {
        process?.destroyForcibly()
        null
    }
}
