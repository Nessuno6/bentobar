package com.meridian11.books.bentobar.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.meridian11.books.bentobar.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * This fork's update check: six times a day (every [PERIOD_MS], when there's a network), one GET of its own latest
 * release on GitHub ([Host.GITHUB], through [Http.get] like every other request), and a notification when it is newer
 * than this install. Nothing about the device or the items is sent. "Check for updates" in Setup turns it off.
 *
 * The gate lets [Host.GITHUB] through only while this check asks ([asking]) and only while it is switched on.
 */
object Updates {
    const val REPO = "Nessuno6/bentobar"
    const val PERIOD_MS = 4 * 60 * 60 * 1000L
    private const val JOB_ID = 0x5570 // "Up"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 0x5570
    private const val PREFS = "updates"

    /** [apkPath]: the APK's path on [Host.GITHUB_RELEASES] ("/<owner>/<repo>/releases/download/<tag>/<name>.apk"). */
    data class Release(val version: String, val page: String, val apkPath: String?)

    /** True only while [check] asks or [download] fetches: the gate's condition for GitHub's hosts. */
    @Volatile var asking = false
        private set

    /** The release's APK into [into] (background thread only), through the gate like every request. */
    fun download(release: Release, into: java.io.File, progress: (Long, Long) -> Unit): Boolean {
        val path = release.apkPath ?: return false
        asking = true
        return try {
            Http.download(Request(Host.GITHUB_RELEASES, path), into, progress) is Reply.Ok
        } finally {
            asking = false
        }
    }

    fun on(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", true)

    fun setOn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
        if (on) schedule(ctx) else ctx.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    /** At start: the periodic job, when the check is on (not persisted, so each start sets it again). */
    fun schedule(ctx: Context) {
        if (!on(ctx)) return
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJob::class.java))
            .setPeriodic(PERIOD_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .build())
    }

    fun installed(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** "1.7.1-notch.2" → [1, 7, 1, 2]: the release's numbers, then the fork's own count (0 without one). */
    fun parts(v: String): List<Int> {
        val clean = v.trim().removePrefix("v")
        val base = clean.substringBefore('-').split('.').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val fork = clean.substringAfter("-notch.", "").takeWhile(Char::isDigit).toIntOrNull() ?: 0
        return (base + List((3 - base.size).coerceAtLeast(0)) { 0 }) + fork
    }

    fun newer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Asks GitHub (background thread only). The newer release, if there is one; [notify]: tell once per version. */
    fun check(ctx: Context, notify: Boolean): Release? {
        if (!on(ctx)) return null
        asking = true
        val reply = try {
            Http.get(Request(Host.GITHUB, "/repos/$REPO/releases/latest"))
        } finally {
            asking = false
        }
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putLong("last", System.currentTimeMillis()).apply()
        val latest = (reply as? Reply.Ok)?.let { parse(it.text) } ?: return null
        val newer = latest.takeIf { newer(it.version, installed(ctx)) } ?: return null
        if (notify && p.getString("announced", null) != newer.version) {
            announce(ctx, newer)
            p.edit().putString("announced", newer.version).apply()
        }
        return newer
    }

    private fun parse(text: String): Release? = runCatching {
        val o = Json.parseToJsonElement(text).jsonObject
        fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull
        val tag = o.s("tag_name") ?: return@runCatching null
        val apk = o["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { (it.s("name") ?: "").endsWith(".apk") }?.s("browser_download_url")
        // Only an address on github.com, kept as its path: the download names the host itself.
        val path = apk?.removePrefix("https://${Host.GITHUB_RELEASES.domain}")?.takeIf { it != apk && it.startsWith("/") }
        Release(tag.removePrefix("v"), o.s("html_url") ?: "https://github.com/$REPO/releases/latest", path)
    }.getOrNull()

    private fun announce(ctx: Context, r: Release) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.updates_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        fun open(code: Int, uri: String) = PendingIntent.getActivity(ctx, code, Intent(Intent.ACTION_VIEW, Uri.parse(uri)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Tapped: updated from inside BentoBar (ui/UpdateActivity), or the release page when it has no APK.
        val update = if (r.apkPath != null) PendingIntent.getActivity(ctx, 1,
            com.meridian11.books.bentobar.ui.UpdateActivity.intent(ctx, r),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) else open(1, r.page)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(com.meridian11.books.bentobar.items.Glyphs.icon(com.meridian11.books.bentobar.util.Sym.UPDATE))
            .setContentTitle(ctx.getString(R.string.updates_available, r.version))
            .setContentText(ctx.getString(R.string.updates_available_text, installed(ctx)))
            .setContentIntent(update)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.updates_whats_new), open(2, r.page)).build())
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }
}

/** Runs [Updates.check] every four hours, off the main thread. */
class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Thread({
            try {
                com.meridian11.books.bentobar.items.Env.init(this)
                Updates.check(this, notify = true)
            } catch (_: Throwable) {
            } finally {
                jobFinished(params, false)
            }
        }, "BentoBar updates").start()
        return true
    }

    override fun onStopJob(params: JobParameters?) = false
}
