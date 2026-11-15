package com.meridian11.books.bentobar.items

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.meridian11.books.bentobar.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shared elevated runner for optional reads (`RootCpu`, `RootMem`, `RootBattery`, `RootThermal`, `RootBt`): prefers
 * Shizuku (ADB shell or root) when running and permitted, falling back to `su` where needed and available.
 * Refused `su` stays refused for the process lifetime so root managers don't re-prompt; granting or starting Shizuku
 * advances [epoch] so previously unavailable readers try again at once. Nothing read is kept or logged.
 */
object Priv {
    const val SHIZUKU_PACKAGE = "moe.shizuku.manager"
    const val REQUEST_CODE = 0x5348

    @Volatile var epoch: Int = 1
        private set

    @Volatile private var suRefused = false
    @Volatile private var initialized = false
    @Volatile private var onStatusChanged: (() -> Unit)? = null
    @Volatile private var userService: IPrivilegedService? = null
    @Volatile private var shizukuUidCache: Int? = null
    @Volatile private var connectLatch: CountDownLatch? = null
    private val serviceLock = Any()

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        shizukuUidCache = runCatching { Shizuku.getUid() }.getOrNull()
        epoch++
        onStatusChanged?.invoke()
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        synchronized(serviceLock) {
            userService = null
            shizukuUidCache = null
            connectLatch?.countDown()
            connectLatch = null
        }
        epoch++
        onStatusChanged?.invoke()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            shizukuUidCache = runCatching { Shizuku.getUid() }.getOrNull()
            epoch++
        }
        onStatusChanged?.invoke()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val stub = binder?.let { IPrivilegedService.Stub.asInterface(it) }
            synchronized(serviceLock) {
                userService = stub
                shizukuUidCache = runCatching { stub?.uid() }.getOrNull() ?: shizukuUidCache
                connectLatch?.countDown()
                connectLatch = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(serviceLock) {
                userService = null
                connectLatch?.countDown()
                connectLatch = null
            }
        }
    }

    fun init(context: Context, onChanged: () -> Unit) {
        onStatusChanged = onChanged
        if (initialized) return
        initialized = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionResult)
        }
    }

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun status(context: Context): PrivRules.ShizukuStatus {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val preV11 = if (binderAlive) runCatching { Shizuku.isPreV11() }.getOrDefault(false) else false
        val granted = if (binderAlive && !preV11) {
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        } else false
        return PrivRules.shizukuStatus(isInstalled(context), binderAlive, preV11, granted)
    }

    fun shizukuUid(): Int? {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return null
        return shizukuUidCache ?: runCatching { Shizuku.getUid() }.getOrNull()?.also { shizukuUidCache = it }
    }

    fun backend(context: Context): PrivRules.Backend =
        PrivRules.backend(status(context), shizukuUid(), suAvailable = !suRefused)

    fun shouldShowRationale(): Boolean =
        runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    fun openShizuku(context: Context): Boolean {
        val intent = runCatching { context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) }.getOrNull() ?: return false
        return Env.launch(intent)
    }

    /** Runs [command] and parses its output with [parse], trying Shizuku first when applicable and falling back to `su`. */
    fun <T> read(
        command: String,
        timeoutMs: Int = 6_000,
        rootOnly: Boolean = false,
        mountMaster: Boolean = false,
        parse: (String) -> T?,
    ): T? {
        val app = runCatching { Env.app }.getOrNull()
        val shizuku = app?.let { status(it) } ?: PrivRules.ShizukuStatus.NOT_INSTALLED
        val uid = shizukuUid()
        if (app != null && PrivRules.tryShizuku(shizuku, uid, rootOnly)) {
            val svc = bindServiceBlocking(app)
            if (svc != null) {
                val decoded = runCatching { PrivRules.decode(svc.run(command, timeoutMs)) }.getOrNull()
                if (decoded == null) {
                    // Dead binder or remote failure: drop stale service so the next attempt can rebind if alive.
                    clearDeadService()
                } else if (decoded.exitCode == 0) {
                    parse(decoded.output)?.let { return it }
                }
            }
        }
        if (PrivRules.trySu(shizuku, uid, suRefused)) {
            runSu(command, timeoutMs.toLong(), mountMaster)?.let { out ->
                parse(out)?.let { return it }
            }
        }
        return null
    }

    /** Companion battery list via Shizuku UserService first, or `app_process RootTool batteries` via `su`. */
    fun companions(): Map<String, DevicesRules.Companion>? {
        val app = runCatching { Env.app }.getOrNull() ?: return null
        val shizuku = status(app)
        val uid = shizukuUid()
        if (PrivRules.tryShizuku(shizuku, uid, rootOnly = false)) {
            val svc = bindServiceBlocking(app)
            if (svc != null) {
                val out = runCatching { svc.batteries() }.getOrNull()
                if (out == null) {
                    clearDeadService()
                } else if (!out.startsWith("error\t")) {
                    return DevicesRules.companions(out)
                }
            }
        }
        if (PrivRules.trySu(shizuku, uid, suRefused)) {
            val apk = app.applicationInfo.sourceDir
            runSu("CLASSPATH=$apk app_process /system/bin ${RootTool::class.java.name} batteries", 8_000L, mountMaster = true)
                ?.let { return DevicesRules.companions(it) }
        }
        return null
    }

    private fun runSu(command: String, timeoutMs: Long, mountMaster: Boolean): String? {
        if (suRefused) return null
        val tries = if (mountMaster) listOf(listOf("su", "--mount-master", "-c", command), listOf("su", "-c", command))
        else listOf(listOf("su", "-c", command))
        for (argv in tries) {
            val r = execProcess(argv, timeoutMs)
            if (r != null && r.exitCode == 0) return r.output
        }
        suRefused = true
        return null
    }

    private fun clearDeadService() {
        synchronized(serviceLock) {
            if (runCatching { userService?.asBinder()?.pingBinder() != true }.getOrDefault(true)) {
                userService = null
            }
        }
    }

    private fun bindServiceBlocking(context: Context): IPrivilegedService? {
        synchronized(serviceLock) {
            userService?.takeIf { runCatching { it.asBinder().pingBinder() }.getOrDefault(false) }?.let { return it }
            userService = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShizukuService::class.java.name))
            .daemon(false)
            .processNameSuffix("shizuku")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        val latch: CountDownLatch
        val shouldBind: Boolean
        synchronized(serviceLock) {
            userService?.takeIf { runCatching { it.asBinder().pingBinder() }.getOrDefault(false) }?.let { return it }
            val existing = connectLatch
            if (existing != null) {
                latch = existing
                shouldBind = false
            } else {
                latch = CountDownLatch(1)
                connectLatch = latch
                shouldBind = true
            }
        }
        if (shouldBind) {
            val ok = runCatching { Shizuku.bindUserService(args, connection); true }.getOrDefault(false)
            if (!ok) {
                synchronized(serviceLock) {
                    if (connectLatch === latch) connectLatch = null
                    latch.countDown()
                }
                return null
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        return synchronized(serviceLock) { userService }
    }
}

/**
 * One command run with Shizuku or `su` on a background thread, at most every [everyMs] while something wants it,
 * turning output into [T] via [parse]. On refusal/absence for the current [Priv.epoch], nothing is shown and it waits
 * until [Priv.epoch] advances (e.g., Shizuku is started or granted). Nothing read is logged.
 */
class PrivReading<T>(
    private val command: String,
    private val everyMs: Long,
    private val timeoutMs: Int = 6_000,
    private val keepEmpty: Boolean = false,
    private val parse: (String) -> T?,
) {
    @Volatile var value: T? = null; private set
    @Volatile private var refusedAtEpoch = -1
    @Volatile private var running = false
    @Volatile private var readAt = 0L

    fun want() {
        val currentEpoch = Priv.epoch
        if (running || refusedAtEpoch == currentEpoch) return
        if ((keepEmpty || value != null) && SystemClock.elapsedRealtime() - readAt < everyMs) return
        running = true
        Thread({ read(currentEpoch) }, "BentoBar priv").start()
    }

    private fun read(currentEpoch: Int) {
        try {
            val parsed = Priv.read(command, timeoutMs, parse = parse)
            if (parsed == null) {
                if (Priv.epoch == currentEpoch) refusedAtEpoch = currentEpoch
            } else {
                value = parsed
                refusedAtEpoch = -1
                readAt = SystemClock.elapsedRealtime()
            }
        } finally {
            running = false
        }
    }
}
