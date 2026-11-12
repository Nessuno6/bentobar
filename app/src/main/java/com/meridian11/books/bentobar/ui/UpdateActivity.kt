package com.meridian11.books.bentobar.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.net.Updates
import java.io.File

/**
 * Updates this fork from inside BentoBar: Android's "Install unknown apps" permission first (asked only now, when
 * the user chose to update), then the release's APK through BentoBar's one way out (net/Updates.download), then
 * Android's package installer. Every prompt is the system's own dialog in the device's theme (One UI's on Samsung).
 */
class UpdateActivity : Activity() {
    private var stage = Stage.DOWNLOADING
    private var failure = R.string.updates_install_failed
    private var dialog: AlertDialog? = null
    private lateinit var bar: ProgressBar
    private val release: Updates.Release
        get() = Updates.Release(
            intent.getStringExtra(EXTRA_VERSION).orEmpty(), intent.getStringExtra(EXTRA_PAGE).orEmpty(), intent.getStringExtra(EXTRA_PATH),
        )

    private enum class Stage { NEED_PERMISSION, DOWNLOADING, INSTALLING, FAILED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Env.init(this)
        // The installer's answer can start this screen afresh (updating the app ends the old process): an answer to
        // handle, not a new update.
        if (intent.action == ACTION_STATUS) { show(); handleStatus(intent); return }
        // Opened with nothing to update: One UI reopens the screen that was on top after an app updates, without its
        // details. That's this screen coming back after the update it just did: say so, and close.
        if (!intent.hasExtra(EXTRA_VERSION)) {
            if (justUpdated()) Toast.makeText(this, R.string.updates_done, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        show()
        if (savedInstanceState == null) start()
    }

    /** The one dialog, in the device's own theme (One UI's on Samsung); [render] changes what it says. */
    private fun show() {
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply { setPadding(pad, 0, pad, 0); addView(bar) }
        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.updates_update_to, release.version))
            .setMessage(" ")
            .setView(box)
            .setPositiveButton(" ", null)
            .setNegativeButton(android.R.string.cancel, null)
            .setOnCancelListener { finish() }
            .create().apply {
                setCanceledOnTouchOutside(false)
                show()
                getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    when (stage) {
                        Stage.NEED_PERMISSION -> askPermission()
                        Stage.FAILED -> start()
                        else -> {}
                    }
                }
                getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener { finish() }
            }
        render()
    }

    private fun render() {
        val d = dialog ?: return
        val busy = stage == Stage.DOWNLOADING || stage == Stage.INSTALLING
        d.setMessage(getString(when (stage) {
            Stage.NEED_PERMISSION -> R.string.updates_permission
            Stage.DOWNLOADING -> R.string.updates_downloading
            Stage.INSTALLING -> R.string.updates_installing
            Stage.FAILED -> failure
        }))
        bar.visibility = if (stage == Stage.DOWNLOADING) View.VISIBLE else View.GONE
        d.setCancelable(!busy)
        d.getButton(DialogInterface.BUTTON_POSITIVE)?.apply {
            visibility = if (stage == Stage.NEED_PERMISSION || stage == Stage.FAILED) View.VISIBLE else View.GONE
            setText(if (stage == Stage.NEED_PERMISSION) R.string.updates_allow else R.string.updates_try_again)
        }
        d.getButton(DialogInterface.BUTTON_NEGATIVE)?.visibility = if (busy) View.GONE else View.VISIBLE
    }

    private fun go(next: Stage) {
        stage = next
        render()
    }

    private fun start() {
        if (release.apkPath == null) { fail(R.string.updates_no_apk); return }
        if (packageManager.canRequestPackageInstalls()) download() else go(Stage.NEED_PERMISSION)
    }

    /** Back from Android's setting: on with the update if the user allowed it. */
    override fun onResume() {
        super.onResume()
        if (stage == Stage.NEED_PERMISSION && packageManager.canRequestPackageInstalls()) download()
    }

    private fun justUpdated(): Boolean = runCatching {
        System.currentTimeMillis() - packageManager.getPackageInfo(packageName, 0).lastUpdateTime < 2 * 60_000
    }.getOrDefault(false)

    private fun askPermission() {
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
    }

    private fun download() {
        bar.progress = 0
        go(Stage.DOWNLOADING)
        val r = release
        Thread({
            val file = File(cacheDir, "update.apk")
            val ok = Updates.download(r, file) { done, total -> if (total > 0) runOnUiThread { bar.progress = (done * 1000 / total).toInt() } }
            runOnUiThread { if (ok) install(file) else fail(R.string.updates_download_failed) }
        }, "BentoBar update").start()
    }

    private fun install(file: File) {
        go(Stage.INSTALLING)
        runCatching {
            val installer = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(packageName) }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("update.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                // The installer's answer comes back here (onNewIntent): a confirmation to show, or how it ended.
                val status = PendingIntent.getActivity(
                    this, id, Intent(intent).setClass(this, UpdateActivity::class.java).setAction(ACTION_STATUS),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(status.intentSender)
            }
        }.onFailure { fail(R.string.updates_install_failed) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_STATUS) handleStatus(intent)
    }

    private fun handleStatus(intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) startActivity(confirm) else fail(R.string.updates_install_failed)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(this, R.string.updates_done, Toast.LENGTH_SHORT).show()
                finish()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> finish()
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> fail(R.string.updates_other_key)
            else -> fail(R.string.updates_install_failed)
        }
    }

    private fun fail(text: Int) {
        failure = text
        go(Stage.FAILED)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_PAGE = "page"
        private const val EXTRA_PATH = "apkPath"
        private const val ACTION_STATUS = "com.meridian11.books.bentobar.UPDATE_STATUS"

        fun intent(context: Context, r: Updates.Release): Intent = Intent(context, UpdateActivity::class.java)
            .putExtra(EXTRA_VERSION, r.version).putExtra(EXTRA_PAGE, r.page).putExtra(EXTRA_PATH, r.apkPath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
