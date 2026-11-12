package com.meridian11.books.bentobar.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.net.Updates
import java.io.File

/**
 * Updates this fork from inside BentoBar: Android's "Install unknown apps" permission first (asked only now, when
 * the user chose to update), then the release's APK through BentoBar's one way out (net/Updates.download), then
 * Android's package installer, which asks the user to confirm as always.
 */
class UpdateActivity : ComponentActivity() {
    private var stage by mutableStateOf(Stage.ASKING)
    private var progress by mutableFloatStateOf(0f)
    private var failure by mutableStateOf(0)
    private val release: Updates.Release
        get() = Updates.Release(
            intent.getStringExtra(EXTRA_VERSION).orEmpty(), intent.getStringExtra(EXTRA_PAGE).orEmpty(), intent.getStringExtra(EXTRA_PATH),
        )

    private enum class Stage { ASKING, NEED_PERMISSION, DOWNLOADING, INSTALLING, FAILED }

    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) download() else stage = Stage.NEED_PERMISSION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Env.init(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val busy = stage == Stage.DOWNLOADING || stage == Stage.INSTALLING
                AlertDialog(
                    onDismissRequest = { if (!busy) finish() },
                    title = { Text(stringResource(R.string.updates_update_to, release.version)) },
                    text = {
                        Column {
                            when (stage) {
                                Stage.ASKING -> Text(stringResource(R.string.updates_preparing))
                                Stage.NEED_PERMISSION -> Text(stringResource(R.string.updates_permission))
                                Stage.DOWNLOADING -> {
                                    Text(stringResource(R.string.updates_downloading))
                                    Spacer(Modifier.height(12.dp))
                                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.padding(vertical = 4.dp))
                                }
                                Stage.INSTALLING -> Text(stringResource(R.string.updates_installing))
                                Stage.FAILED -> Text(stringResource(failure))
                            }
                        }
                    },
                    confirmButton = {
                        when (stage) {
                            Stage.NEED_PERMISSION -> TextButton({ askPermission() }) { Text(stringResource(R.string.updates_allow)) }
                            Stage.FAILED -> TextButton({ start() }) { Text(stringResource(R.string.updates_try_again)) }
                            else -> {}
                        }
                    },
                    dismissButton = { if (!busy) TextButton({ finish() }) { Text(stringResource(android.R.string.cancel)) } },
                )
            }
        }
        // The installer's answer can start this screen afresh: updating the app itself ends the old process, and the
        // "installed" answer then reaches the new one. That's an answer to handle, not a new update to begin.
        if (intent.action == ACTION_STATUS) handleStatus(intent)
        else if (savedInstanceState == null) start()
    }

    private fun start() {
        if (release.apkPath == null) { fail(R.string.updates_no_apk); return }
        if (packageManager.canRequestPackageInstalls()) download() else stage = Stage.NEED_PERMISSION
    }

    private fun askPermission() {
        permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
    }

    private fun download() {
        stage = Stage.DOWNLOADING
        progress = 0f
        val r = release
        Thread({
            val file = File(cacheDir, "update.apk")
            val ok = Updates.download(r, file) { done, total -> if (total > 0) runOnUiThread { progress = done.toFloat() / total } }
            runOnUiThread { if (ok) install(file) else fail(R.string.updates_download_failed) }
        }, "BentoBar update").start()
    }

    private fun install(file: File) {
        stage = Stage.INSTALLING
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
                android.widget.Toast.makeText(this, R.string.updates_done, android.widget.Toast.LENGTH_SHORT).show()
                finish()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> finish()
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> fail(R.string.updates_other_key)
            else -> fail(R.string.updates_install_failed)
        }
    }

    private fun fail(text: Int) {
        failure = text
        stage = Stage.FAILED
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
