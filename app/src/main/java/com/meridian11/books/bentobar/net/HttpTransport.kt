package com.meridian11.books.bentobar.net

import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLException

/**
 * A request over HTTPS with the platform's own HttpURLConnection. The address is put together here
 * and nowhere else: `https://`, the [Host]'s domain, a path of plain characters, the encoded query.
 * A redirect is an answer (a status), never followed, so nothing can lead to a fourth host.
 *
 * Whatever goes wrong becomes a [Reply.Failed]. No exception leaves this class and none is logged:
 * the platform puts the address into exception messages, and an address can hold a key.
 */
class HttpTransport(
    /** Opens the connection for an address. Only tests pass one (to reach a server on this machine). */
    private val open: (String) -> HttpURLConnection = ::openChecked,
    private val connectMs: Int = Http.CONNECT_MS,
    private val readMs: Int = Http.READ_MS,
    private val maxBytes: Int = Http.MAX_BYTES,
    private val totalMs: Int = Http.TOTAL_MS,
) : Transport {

    override fun get(request: Request): Reply {
        val address = address(request) ?: return Reply.Failed(Why.UNREADABLE)
        val deadline = System.nanoTime() + totalMs * 1_000_000L
        var connection: HttpURLConnection? = null
        return try {
            val c = open(address).also { connection = it }
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.requestMethod = "GET"
            // Not the platform's default, which names the device model and the Android build.
            c.setRequestProperty("User-Agent", Http.USER_AGENT)
            c.setRequestProperty("Accept", "application/json")
            // Asked for by name and unpacked below, so Android and the JVM (the unit tests) behave alike.
            c.setRequestProperty("Accept-Encoding", "gzip")
            val status = c.responseCode
            if (status !in 200..299) return Reply.Failed(Why.STATUS, status, retryAfter(c))
            val packed = c.contentEncoding.orEmpty().trim().equals("gzip", ignoreCase = true)
            val bytes = c.inputStream.let { if (packed) GZIPInputStream(it) else it }.use { read(it, deadline) }
                ?: return Reply.Failed(Why.TOO_LARGE, status)
            Reply.Ok(String(bytes, Charsets.UTF_8))
        } catch (t: Throwable) {
            Reply.Failed(why(t))
        } finally {
            try { connection?.disconnect() } catch (_: Throwable) {}
        }
    }

    /**
     * A file (the fork's update APK) into [into]. GitHub answers the release address with one redirect to its storage:
     * followed only if it leads over HTTPS to [Host.GITHUB_ASSETS], and only once; anything else is a [Why.STATUS].
     * At most [MAX_FILE_BYTES]; [progress] gets bytes so far and the total (-1 when unknown).
     */
    fun download(request: Request, into: java.io.File, progress: (Long, Long) -> Unit): Reply {
        val address = address(request) ?: return Reply.Failed(Why.UNREADABLE)
        var connection: HttpURLConnection? = null
        return try {
            var c = connectFile(address).also { connection = it }
            var status = c.responseCode
            if (status in 300..399) {
                val next = c.getHeaderField("Location")?.let { runCatching { URL(it) }.getOrNull() }
                    ?.takeIf { it.protocol == "https" && it.host == Host.GITHUB_ASSETS.domain }
                    ?: return Reply.Failed(Why.STATUS, status)
                c.disconnect()
                c = connectFile(next.toString()).also { connection = it }
                status = c.responseCode
            }
            if (status !in 200..299) return Reply.Failed(Why.STATUS, status)
            val total = c.contentLengthLong
            if (total > MAX_FILE_BYTES) return Reply.Failed(Why.TOO_LARGE, status)
            var done = 0L
            c.inputStream.use { input ->
                into.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        done += n
                        if (done > MAX_FILE_BYTES) return Reply.Failed(Why.TOO_LARGE, status)
                        out.write(buffer, 0, n)
                        progress(done, total)
                    }
                }
            }
            if (total > 0 && done != total) Reply.Failed(Why.UNREADABLE) else Reply.Ok("")
        } catch (t: Throwable) {
            Reply.Failed(why(t))
        } finally {
            try { connection?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun connectFile(address: String): HttpURLConnection = open(address).apply {
        connectTimeout = connectMs
        readTimeout = 30_000
        instanceFollowRedirects = false
        useCaches = false
        requestMethod = "GET"
        setRequestProperty("User-Agent", Http.USER_AGENT)
    }

    /**
     * At most [maxBytes], or null if there is more: a reply that large is nobody's forecast. Each
     * read has its own time limit, and so has the whole: past [deadline] (System.nanoTime) an answer
     * that still trickles in is given up as a timeout.
     */
    private fun read(stream: InputStream, deadline: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > maxBytes) return null
            out.write(buffer, 0, n)
            if (System.nanoTime() - deadline > 0) throw SocketTimeoutException()
        }
    }

    private fun retryAfter(c: HttpURLConnection): Long? =
        c.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it in 0..86_400 }

    companion object {
        /** The largest update APK BentoBar downloads. */
        const val MAX_FILE_BYTES = 100L * 1024 * 1024
        private val PATH = Regex("(/[A-Za-z0-9._~-]+)+")

        /**
         * The address of [request], or null if its path isn't a plain one: it must start with "/"
         * and hold only letters, digits and `. _ ~ -` between slashes (so no `//`, `@`, `?`, `#`,
         * backslash or space), and no `..`.
         */
        fun address(request: Request): String? {
            if (!PATH.matches(request.path) || request.path.contains("..")) return null
            val query = request.query.joinToString("&") { (name, value) -> encode(name) + "=" + encode(value) }
            return "https://" + request.host.domain + request.path + if (query.isEmpty()) "" else "?$query"
        }

        private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

        /** The real opener: once more, only HTTPS and only BentoBar's hosts. Nothing is connected yet when it returns. */
        internal fun openChecked(address: String): HttpURLConnection {
            val url = URL(address)
            check(url.protocol == "https" && Host.entries.any { it.domain == url.host }) { "not one of BentoBar's hosts" }
            return url.openConnection() as HttpURLConnection
        }

        /** What went wrong, as one of a few kinds. The exception itself is dropped here. */
        internal fun why(t: Throwable): Why = when (t) {
            is SocketTimeoutException -> Why.TIMEOUT
            is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketException, is SSLException -> Why.OFFLINE
            else -> Why.UNREADABLE
        }
    }
}
