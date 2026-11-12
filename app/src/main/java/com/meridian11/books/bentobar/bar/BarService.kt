package com.meridian11.books.bentobar.bar

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.view.Display
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.data.ColorMode
import com.meridian11.books.bentobar.data.HiddenMode
import com.meridian11.books.bentobar.data.shows
import com.meridian11.books.bentobar.data.behindChevron
import com.meridian11.books.bentobar.data.moved
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Pill
import com.meridian11.books.bentobar.data.Position
import com.meridian11.books.bentobar.data.BarConfig
import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.TextSize
import com.meridian11.books.bentobar.items.Caffeine
import com.meridian11.books.bentobar.items.Chips
import com.meridian11.books.bentobar.items.DisplaysItem
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.Items
import com.meridian11.books.bentobar.items.MenuHost
import com.meridian11.books.bentobar.items.Ticker
import com.meridian11.books.bentobar.ui.MainActivity
import com.meridian11.books.bentobar.ui.MenuRoom
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * BentoBar's accessibility service. It reads only SystemUI's status bar window (layout and text
 * colour), draws BentoBar's items in overlay windows on top of it, and performs the global
 * actions of the Tools menu when asked. It doesn't observe keys, touches or other apps' windows.
 */
class BarService : AccessibilityService() {
    private var bar: BarController? = null
    /** A strip on each external display's desktop taskbar (DeX), by display id. */
    private val desks = HashMap<Int, BarController>()
    private val displayManager by lazy { getSystemService(android.hardware.display.DisplayManager::class.java) }
    private val displays = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = syncDesks()
        override fun onDisplayRemoved(displayId: Int) = syncDesks()
        override fun onDisplayChanged(displayId: Int) {}
    }

    /**
     * A controller for each external display (a monitor, a wireless one), none for the device's own screen, virtual
     * displays (screen recording) or private ones. One that finds no taskbar there just stays hidden.
     */
    private fun syncDesks() {
        if (bar == null) return
        val wanted = runCatching { displayManager.displays.toList() }.getOrDefault(emptyList()).filter {
            it.displayId != Display.DEFAULT_DISPLAY && it.flags and Display.FLAG_PRESENTATION != 0 && it.flags and Display.FLAG_PRIVATE == 0
        }.map { it.displayId }.toSet()
        (desks.keys - wanted).forEach { id -> desks.remove(id)?.stop(); Log.i(TAG, "desk strip $id stopped") }
        (wanted - desks.keys).forEach { id -> desks[id] = BarController(this, id).also { it.start() }; Log.i(TAG, "desk strip $id started") }
    }

    override fun onServiceConnected() {
        Env.init(this)
        Env.service = this
        bar?.stop() // connected again without an unbind: the old strip's windows mustn't stay up
        bar = BarController(this).also { it.start() }
        runCatching { displayManager.registerDisplayListener(displays, android.os.Handler(mainLooper)) }
        syncDesks()
        Chips.update(this)
        com.meridian11.books.bentobar.ui.Setup.refresh(this)
        Log.i(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) { bar?.onEvent(event); desks.values.forEach { it.onEvent(event) } }
    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bar?.onConfigChanged()
        desks.values.forEach { it.onConfigChanged() }
    }

    override fun onUnbind(intent: Intent?): Boolean { shutdown(); return super.onUnbind(intent) }
    override fun onDestroy() { shutdown(); super.onDestroy() }

    /**
     * Runs on unbind and again on destroy; a rebind makes a new controller in [onServiceConnected].
     * (UiAutomation, e.g. `uiautomator dump`, unbinds every accessibility service while it runs.)
     */
    private fun shutdown() {
        val stopping = bar
        bar = null // first: whatever stop() does, it runs once per controller
        stopping?.stop()
        runCatching { displayManager.unregisterDisplayListener(displays) }
        desks.values.forEach { it.stop() }; desks.clear()
        if (Env.service === this) Env.service = null
        Chips.update(this)
        com.meridian11.books.bentobar.ui.Setup.refresh(this)
    }

    fun controller(): BarController? = bar
    /** The strip on another display's taskbar, if there is one. */
    fun controller(displayId: Int): BarController? = desks[displayId]

    companion object { const val TAG = "BentoBar" }
}

/** Item types still shown while presenting (when active): a running timer, a meeting about to start. */
private val PRESENTING_TYPES = setOf("timer", "countdown", "event")

/**
 * One strip and its popups on one display: the device's own screen ([displayId] the default), in its status bar, or an
 * external display's desktop taskbar along the bottom ([desk]: Samsung DeX, which has no status bar there), where the
 * strip ends right before the system icons and popups open above it.
 */
class BarController(private val service: AccessibilityService, val displayId: Int = Display.DEFAULT_DISPLAY) {
    private val desk = displayId != Display.DEFAULT_DISPLAY
    /** The display's own context: windows made with it go on that display (an accessibility service's keeps its token). */
    private val ctx: Context = if (!desk) service
        else service.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(displayId)?.let { service.createDisplayContext(it) } ?: service
    private val tag = if (desk) "${BarService.TAG}:$displayId" else BarService.TAG
    /** Who keeps the item clock running: one name a strip. */
    private val who = if (desk) "bar$displayId" else "bar"
    /** Items that don't fit this strip: the app's own list for the device's screen, one of its own on another display. */
    private val overflow = if (desk) kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet()) else BarOverflow.ids
    /** What the app's settings say of the strip: the device's screen's alone. */
    private var status: BarStatus
        get() = BarStatus.current.value
        set(v) { if (!desk) BarStatus.current.value = v }
    /** Where the strip sits: against the system icons, always, on a taskbar. */
    private fun position(cfg: BarConfig) = if (desk) Position.RIGHT else cfg.position
    /** "Show on" lets this display have a strip. */
    private fun wanted(cfg: BarConfig) = cfg.screens == com.meridian11.books.bentobar.data.Screens.ALL ||
        (cfg.screens == com.meridian11.books.bentobar.data.Screens.OTHER) == desk
    /**
     * The display's size. A display context isn't a window's: its window manager measures the device's own screen,
     * so another display's size is asked of the display itself.
     */
    @Suppress("DEPRECATION")
    private fun screenBounds(): Rect = if (!desk) wm.currentWindowMetrics.bounds
        else ctx.display.let { d -> android.graphics.Point().also { d.getRealSize(it) }.let { Rect(0, 0, it.x, it.y) } }
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val pm = service.getSystemService(PowerManager::class.java)
    private val km = service.getSystemService(KeyguardManager::class.java)
    private val io = Executors.newSingleThreadExecutor()
    /** [io] for screenshot results; one arriving after stop() shut it down is dropped, not thrown. */
    private val callbacks = java.util.concurrent.Executor { r -> runCatching { io.execute(r) } }
    private val density get() = ctx.resources.displayMetrics.density

    // Read by the strip's composition.
    private val expanded = object {
        private val state = mutableStateOf(false)
        var value: Boolean
            get() = state.value
            set(v) {
                state.value = v; Ticker.revealHidden = v || menuKey == "bentobar"; if (v) Ticker.refresh()
                // Out: a click anywhere else folds them away again (see [outsideClick]).
                strip.watchOutside(v)
            }
    }
    private val look = mutableStateOf(StripLook(Color.White, true, TextSize.DEFAULT, 10.dp, Pill.NONE))
    private val maxWidth = mutableIntStateOf(0)
    private val heightDp = mutableStateOf(36.dp)

    private val strip = Overlay(ctx, if (desk) "BentoBar $displayId" else "BentoBar", onOutside = { outsideClick() }).apply { params.width = 1; watchOutside(false) }

    /** A click outside the strip while hidden items are out folds them away, unless a menu is open (it closes itself). */
    private fun outsideClick() {
        if (menuKey != null || !expanded.value) return
        expanded.value = false; pinned = false
        if (Store.config.value.pinnedOpen) Store.update { it.copy(pinnedOpen = false) }
    }
    private var menu: MenuWindow? = null
    /** On a taskbar: the popup just left for another, up until that one starts to rise ([closeMenu]). */
    private var handOff: MenuWindow? = null
    private val dropHandOff: Runnable = object : Runnable {
        override fun run() { main.removeCallbacks(this); handOff?.close(now = true); handOff = null }
    }
    /** The new popup is rising: the one it replaces dissolves now. */
    private fun dissolveHandOff() { main.removeCallbacks(dropHandOff); handOff?.close(dissolve = true); handOff = null }
    // Observable, so the strip's highlight can stay on the item whose popup is open (StripHost).
    private var menuKey: String? by mutableStateOf(null)
    /**
     * The popup a press on its own item just closed: that press's click, which reaches the strip after the
     * popup heard of the press, is spent (it would open the popup again). Only that one click, and only for a
     * moment: a second click turns the popup round while it is still folding away.
     */
    private var spentKey: String? = null
    private var spentAt = 0L
    /** A popup still folding away, and its key: clicked again meanwhile, it turns round. */
    private var leaving: MenuWindow? = null
    private var leavingKey: String? = null
    /** It is folding away so that something can run once it is off the screen (a screenshot): it doesn't turn round. */
    private var leavingThen = false
    private var snap: BarSnapshot? = null
    private var sampled: BarColors? = null
    private var overlayIds = emptySet<Int>()
    private var hovering = false
    private var pinned = false
    private var started = false
    /** Where each item was last drawn (window coordinates), for anchoring menus from tests. */
    val placed = HashMap<String, Rect>()

    private val scanNow = Runnable { scan() }
    private val quickCheck = Runnable { check(full = false) }
    /**
     * Status bar icons come and go without window changes. Every 2 s while shown, refresh just the
     * spacer node (one call) and read the whole bar again only if it moved, or every 30 s.
     */
    private val poll = object : Runnable {
        override fun run() {
            if (!pm.isInteractive) return // screen off: nothing to show; SCREEN_ON restarts this
            if (strip.shown) lightCheck() else check(full = false)
            main.postDelayed(this, if (strip.shown) 2_000 else 5_000)
        }
    }
    private var lastFullScan = 0L
    private var lastTaskbar: Int? = -1
    /** The app windows against the bar's lower edge and under the bar, as [check] last saw them. */
    private var neighbours = BarNeighbours()

    private fun lightCheck() {
        val s = snap ?: return scan()
        val node = s.spacerNode
        // A bar without the DesktopStatusBarSpacer node has nothing cheap to re-check: a full scan
        // (with clearCache) every 2 s is costly, so between full scans only the window list is read.
        if (node == null) return if (SystemClock.uptimeMillis() - lastFullScan > 10_000) scan() else check(full = false)
        if (SystemClock.uptimeMillis() - lastFullScan > 30_000 || !node.refresh()) return scan()
        // Against the spacer's own last bounds: with the spacer squeezed to nothing, the free area is
        // the widest gap instead, and comparing with that would run a full scan every time.
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r != s.spacer) scan()
    }
    private val expandOnHover = Runnable { if (hovering) expanded.value = true }
    private val collapse: Runnable = object : Runnable {
        override fun run() {
            // A slider held by a pointer that has wandered off the strip: its item stays where it is until it lets go.
            if (sliding) { main.postDelayed(this, 300); return }
            if (!hovering && menuKey == null && expanded.value) {
                Log.i(tag, "collapse (pinned=$pinned autoCollapse=${Store.config.value.autoCollapseSec}s)")
                expanded.value = false; pinned = false
                if (Store.config.value.pinnedOpen) Store.update { it.copy(pinnedOpen = false) }
            }
        }
    }
    /** A pointer holds an item's slider: between its first report and the one that says `done`. */
    private var sliding = false
    private val awakeExpiry = Runnable { Caffeine.check() }
    private val sample = Runnable { sampleColor() }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) closeMenu(now = true)
            updateAwake()
            main.post(scanNow)
            if (i.action == Intent.ACTION_SCREEN_ON) {
                main.removeCallbacks(poll); main.postDelayed(poll, 2_000)
                // The chip's next change is timed on a clock that stops while the device sleeps: catch up.
                Chips.update(service)
            }
        }
    }
    // A live wallpaper can report new colours every second: a quick cause, two readings.
    private val wallpaper = WallpaperManager.OnColorsChangedListener { _, _ -> readSoon(ColorWatch.Cause.QUICK, 300) }

    fun start() {
        started = true
        // Pinned open with ‹ before a restart: open again.
        Store.config.value.let { if (it.pinnedOpen && it.hiddenMode != HiddenMode.SHOW_ALL) { expanded.value = true; pinned = true } }
        service.registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        })
        runCatching { WallpaperManager.getInstance(service).addOnColorsChangedListener(wallpaper, main) }
        scope.launch { Store.config.collect { onConfig() } }
        scope.launch {
            Caffeine.until.collect { until ->
                updateAwake()
                main.removeCallbacks(awakeExpiry)
                if (until != 0L && until != Caffeine.FOREVER) main.postDelayed(awakeExpiry, (until - System.currentTimeMillis()).coerceAtLeast(0) + 200)
            }
        }
        scope.launch { DisplaysItem.keepingAwake.collect { updateAwake() } }
        scan()
        main.postDelayed(poll, 2_000)
        armChecks()
    }

    fun stop() {
        if (!started) return // stopped already: the status and the ticker may be a newer controller's by now
        started = false
        if (!desk) BarLook.current.value = null
        status = BarStatus.STOPPED
        overflow.value = emptySet()
        runCatching { service.unregisterReceiver(screen) }
        runCatching { WallpaperManager.getInstance(service).removeOnColorsChangedListener(wallpaper) }
        closeMenu(now = true)
        hideTip(now = true)
        main.removeCallbacksAndMessages(null) // after closeMenu, which posts a collapse
        strip.destroy()
        if (screenLock.isHeld) screenLock.release()
        Ticker.stop(who)
        scope.cancel()
        io.shutdown()
    }

    /** Window events, logged only where asked for: `adb shell setprop log.tag.BentoBarEvents DEBUG`. */
    private val EVENTS_TAG = "BentoBarEvents"

    fun onEvent(e: AccessibilityEvent) {
        if (tracing) trace("event windows id=${e.windowId} changes=0x${Integer.toHexString(e.windowChanges)} ours=${e.windowId in overlayIds}")
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (Log.isLoggable(EVENTS_TAG, Log.DEBUG)) Log.d(EVENTS_TAG, "windows changed id=${e.windowId} changes=0x${Integer.toHexString(e.windowChanges)} ours=${e.windowId in overlayIds}")
                if (e.windowId in overlayIds) return // our own windows resizing
                // Another app in front (focus or the active window moved): the bar most often changes now. A check soon,
                // and quick ones for a while. Other windows coming, going or moving start the quick checks too.
                if (e.windowChanges and SWITCHED != 0) stir(ColorWatch.SWITCH_MS)
                else if (e.windowChanges and RELEVANT != 0) stir(ColorWatch.CHECK_FAST_MS)
                // Only windows appearing, going or moving can hide or cover the bar; titles, focus
                // and stacking changes (every page load, every terminal command) can't.
                if (e.windowChanges and RELEVANT == 0) return
                // The status bar animates in and out: look again as the animation settles.
                main.removeCallbacks(quickCheck)
                for (delay in longArrayOf(100, 450, 900)) main.postDelayed(quickCheck, delay)
            }
        }
    }

    /** Rotation, density or resolution: a menu or tooltip placed for the old display would be off, so close them. */
    fun onConfigChanged() {
        closeMenu(now = true); hideTip(now = true)
        main.postDelayed(scanNow, 200); readSoon(ColorWatch.Cause.SLOW, 500)
    }

    private var lastColor: ColorMode? = null

    private fun onConfig() {
        if (!started) return
        // Colour back to "Match the status bar" (or its opposite): read the bar again (nothing else would until it changes).
        Store.config.value.color.let { if (it != lastColor) { if (it.reads && lastColor != null) readSoon(ColorWatch.Cause.QUICK, 100); lastColor = it } }
        armChecks()
        // Switched to Show everything (or presenting) with hidden items out: nothing can fold them back.
        Store.config.value.let { if ((it.hiddenMode == HiddenMode.SHOW_ALL || it.presenting) && expanded.value) { expanded.value = false; pinned = false } }
        applyLook()
        if (Ticker.running) Ticker.refresh()
        scan()
        Chips.update(service)
    }

    // ---- where the status bar is -------------------------------------------------------------

    private fun scan() = check(full = true)

    /**
     * Where the status bar is and whether to show the strip. A cheap pass reads just the window list;
     * the status bar's node tree is read only when [full] or the bar window itself changed.
     */
    private fun check(full: Boolean) {
        if (!started) return
        val metrics = if (desk) null else wm.currentWindowMetrics
        val screen = screenBounds()
        val screenW = screen.width()
        val screenH = screen.height()
        val inset = metrics?.windowInsets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())?.top ?: 0
        val maxH = (if (inset > 0) inset else (48 * density).toInt()) + 4
        val windows = runCatching { if (desk) service.windowsOnAllDisplays.get(displayId).orEmpty() else service.windows }.getOrDefault(emptyList())
        overlayIds = windows.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.map { it.id }.toSet()
        val barWindow = if (desk) StatusBarScan.findTaskbar(windows, screenW, screenH, (TASKBAR_MAX_DP * density).toInt())
            else StatusBarScan.findWindow(windows, screenW, maxH)
        val barBounds = barWindow?.let { Rect().also { r -> it.getBoundsInScreen(r) } }
        if (desk && barWindow?.id != lastTaskbar) {
            lastTaskbar = barWindow?.id
            Log.i(tag, "taskbar ${barBounds?.toShortString() ?: "none"} on ${screenW}x$screenH, ${windows.size} windows")
        }
        val prev = snap
        // A see-through panel open over everything (a Samsung tablet's edge panel) takes the status bar out of the
        // list, but the bar still shows: the strip stays as it was.
        val panel = !desk && windows.any { w ->
            w.type == AccessibilityWindowInfo.TYPE_SYSTEM && Rect().also { w.getBoundsInScreen(it) }.let { r ->
                StatusBarScan.overhangs(r.left, r.top, r.right, r.bottom, screenW, screenH) }
        }
        val s = when {
            barWindow == null -> if (panel) prev else null
            !full && prev != null && prev.windowId == barWindow.id && prev.bar == barBounds -> prev
            else -> {
                lastFullScan = SystemClock.uptimeMillis()
                // Without content-change events the node cache can go stale: read the status bar fresh.
                service.clearCache()
                runCatching { if (desk) StatusBarScan.scanTaskbar(barWindow) else StatusBarScan.scan(barWindow) }.onFailure { Log.w(tag, "scan failed", it) }.getOrNull()
                    // No node tree (SystemUI mid-change): the whole bar would cover its icons. Keep the last
                    // reading of this same bar (without its cached node); with none, stay hidden until a scan can read it.
                    ?.let { if (it.summary != StatusBarScan.NO_TREE) it else prev?.takeIf { p -> p.windowId == it.windowId && p.bar == it.bar }?.copy(spacerNode = null) }
            }
        }
        val newWindow = s?.windowId != snap?.windowId
        snap = s
        val cfg = Store.config.value
        // On tablets the shade and Quick Settings slide over the status bar; our overlay would sit
        // on top of them. Only a big system window counts: on the desktop bar the panels open as
        // popups below the bar, and small system UI near the top shouldn't blink BentoBar away.
        // The screenshot UI is a big, mostly see-through system window too ("Screenshot preview",
        // full screen while it animates), but it doesn't cover the bar: hiding for it made the strip
        // vanish for the seconds the preview shows.
        val cover = if (s == null) null else windows.firstOrNull { w ->
            val r = Rect().also { w.getBoundsInScreen(it) }
            w.type == AccessibilityWindowInfo.TYPE_SYSTEM && w.id != s.windowId && Rect.intersects(r, s.free) &&
                r.height() >= s.bar.height() * 4 && w.title?.contains("Screenshot", ignoreCase = true) != true &&
                !StatusBarScan.overhangs(r.left, r.top, r.right, r.bottom, screenW, screenH)
        }
        val covered = cover != null
        val show = s != null && !covered && cfg.enabled && wanted(cfg) && pm.isInteractive && !km.isKeyguardLocked
        if (!show) status = when {
            !pm.isInteractive || km.isKeyguardLocked -> BarStatus.ASLEEP
            !cfg.enabled || !wanted(cfg) -> BarStatus.HIDDEN_BY_USER
            s == null -> BarStatus.NO_BAR
            else -> BarStatus.COVERED
        }
        // An app window settled against the bar's lower edge (maximized, or snapped to a side), or a
        // full-screen one lies under the bar: SystemUI then gives the bar a background of its own, or that
        // app's light or dark icons, and takes them away when the window leaves, without an event of the
        // bar's own. Only the windows' ids and bounds are looked at, from the list at hand.
        val moved = s != null && !desk && neighbours.next(s.bar.bottom, s.bar.width(),
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .map { w -> Rect().also { w.getBoundsInScreen(it) }.let { AppWindow(w.id, it.left, it.top, it.right, it.bottom) } })
            .let { (seen, moved) -> neighbours = seen; moved }
        if (show) {
            place(s!!, screenW)
            // A new status bar window can look different (see readSoon for the other causes).
            if (newWindow) readSoon(ColorWatch.Cause.QUICK, 250)
            else if (moved) { watch.windows(SystemClock.uptimeMillis()); arm() }
        } else {
            if (strip.shown) Log.i(tag, "bar hidden (statusBar=${s != null} covered=${cover?.let { "${it.title} " + Rect().also { r -> it.getBoundsInScreen(r) }.toShortString() }} " +
                "enabled=${cfg.enabled} interactive=${pm.isInteractive} locked=${km.isKeyguardLocked})")
            closeMenu(now = true)
            hideTip(now = true)
            cancelDrag() // the strip's window goes, and with it the release that would end a drag
            strip.hide()
            // Readings still to come were for a strip that is gone: its return asks again.
            watch.clear(); main.removeCallbacks(sample)
            updateAwake()
            Ticker.stop(who)
        }
    }

    /** adb `debug trace on|off`: logs every move and resize with its cause, to chase jitter. */
    private var tracing = false
    private var lastPlace = ""
    private fun trace(what: String) { if (tracing) Log.i(tag, "trace ${SystemClock.uptimeMillis() % 100_000} $what") }

    private fun place(s: BarSnapshot, screenW: Int) {
        val cfg = Store.config.value
        val gap = (6 * density).toInt()
        val p = strip.params
        p.height = s.bar.height()
        p.y = s.bar.top
        // Without a pill the strip's ends are only room for the highlight's round ends: the window reaches that much
        // further out, into the gap, so the items stay 6 dp from the free area's edges as they always were.
        val ends = (STRIP_PILL_PADDING.value * density).toInt()
        val out = if (cfg.pill == Pill.NONE) ends else 0
        when (position(cfg)) {
            Position.RIGHT -> { p.gravity = Gravity.TOP or Gravity.RIGHT; p.x = screenW - s.free.right + gap - out }
            Position.LEFT -> { p.gravity = Gravity.TOP or Gravity.LEFT; p.x = s.free.left + gap - out }
            Position.CENTER -> { p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; p.x = s.free.centerX() - screenW / 2 }
        }
        // The whole strip fits the free area: items, the pill's own padding and (decided by the strip,
        // from what fits) room for ‹.
        val pillPad = if (cfg.pill == Pill.NONE) 0 else 2 * ends
        maxWidth.intValue = (s.free.width() - 2 * gap - pillPad).coerceAtLeast(0)
        heightDp.value = (s.bar.height() / density).dp
        val where = "free=${s.free.toShortString()} x=${p.x} maxW=${maxWidth.intValue} [${s.summary}]"
        if (where != lastPlace) { trace("place $where"); lastPlace = where }
        status = if (maxWidth.intValue == 0) BarStatus.NO_ROOM else BarStatus.SHOWN
        if (!strip.shown) {
            stripEmpty = false // a new window starts visible; its first measure says whether it has content
            strip.show { StripHost() }
            // Android refused the window (the service is going): nothing is on screen, so nothing may run or go online.
            // The next look at the bar tries again.
            if (!strip.shown) { status = BarStatus.STOPPED; return }
            updateAwake()
            // Back on screen (after a full-screen app, the lock screen, a covering panel): the bar may
            // look different now, or only in a while (after an unlock it kept the lock screen's look for
            // seconds), and a reading owed while hidden is taken here.
            readSoon(ColorWatch.Cause.SLOW, 250)
            Ticker.start(who)
            Log.i(tag, "bar shown: bar=${s.bar.toShortString()} free=${s.free.toShortString()} [${s.summary}]")
        } else strip.relayout()
    }

    // ---- colour ------------------------------------------------------------------------------

    /**
     * Asks for colour readings after something that can change the bar's look. A reading is a
     * screenshot of SystemUI's status bar WINDOW only (its own surface, no app content), read at the
     * clock ([BarPixels]). On some devices that surface is glyphs on transparent; on others it's opaque
     * while an app is maximized (white glyphs on black) and see-through over the wallpaper otherwise.
     *
     * The bar gives no sign when it changes, and it changes late, so one cause is followed by a few
     * readings spread over the time the bar may take ([ColorWatch]): two within a second and a half
     * after a change of windows or of the wallpaper, six over ten seconds when the strip comes back on
     * screen or the theme or the display changed. Causes that keep coming are held to a budget there.
     *
     * Readings are taken only while the strip is on screen; a cause while it's hidden waits until the
     * strip shows ([place] asks then). The causes are the moments the bar can change: a new status bar
     * window, the strip coming back on screen, a window settling against the bar's lower edge or under
     * it, or leaving ([check]), a theme, display or wallpaper change, and switching back to "Match the
     * status bar". Never on a timer: an accessibility service taking screenshots every 30 s looks like
     * screen capture to Android's threat detection.
     */
    private fun readSoon(cause: ColorWatch.Cause, firstMs: Long) {
        if (!started || !strip.shown) return
        // Not while the strip has nothing to draw: the reading waits until it has ([ColorWatch.pause]).
        if (stripEmpty) return watch.owe(cause)
        watch.cause(cause, SystemClock.uptimeMillis(), firstMs)
        arm()
    }

    /** Times the next reading [watch] has to come, if any. */
    private fun arm() {
        main.removeCallbacks(sample)
        watch.due(SystemClock.uptimeMillis())?.let { main.postDelayed(sample, it) }
    }

    private val watch = ColorWatch()

    /** A reading; [check] when it is "Keep checking the status bar"'s, not one a cause asked for. */
    private fun sampleColor(check: Boolean = false) {
        if (!started || !strip.shown || !pm.isInteractive) return watch.clear()
        if (stripEmpty) return watch.pause()
        val s = snap
        if (s == null || !Store.config.value.color.reads) { watch.clear(); return applyLook() }
        watch.taken(SystemClock.uptimeMillis(), check)
        service.takeScreenshotOfWindow(s.windowId, callbacks, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                val c = runCatching { barColors(r, s) }.getOrNull()
                // A result can arrive after stop(): it mustn't paint a strip that's gone.
                main.post { if (started) onSample(c, check) }
            }
            override fun onFailure(code: Int) {
                main.post { if (started) { if (!check) Log.i(tag, "status bar colour sample failed ($code)"); onSample(null, check) } }
            }
        })
    }

    /**
     * "Keep checking the status bar": the bar changes its colours with no event (an app turning its page dark, a
     * system that tints the bar its own way), so while the strip shows and reads the bar, it is read on a timer
     * whenever no series a cause asked for is under way: every second for a while after anything moved, every four
     * seconds once all is still ([ColorWatch.checkEvery]).
     */
    private val keepChecking = object : Runnable {
        override fun run() {
            nextCheck = Long.MAX_VALUE
            if (!checking()) return
            val now = SystemClock.uptimeMillis()
            if (strip.shown && !stripEmpty && pm.isInteractive && watch.idle(now)) sampleColor(check = true)
            val next = ColorWatch.checkEvery(now - stirred)
            main.postDelayed(this, next); nextCheck = now + next
        }
    }

    /** When something last moved that can change the bar's look. */
    private var stirred = 0L

    private fun checking() = Store.config.value.let { started && it.keepChecking && it.color.reads }

    /** Something moved: quick checks for a while, the next one [firstMs] from now unless one is due sooner. */
    private fun stir(firstMs: Long) {
        val now = SystemClock.uptimeMillis()
        stirred = now
        if (!checking() || now + firstMs >= nextCheck) return
        main.removeCallbacks(keepChecking)
        main.postDelayed(keepChecking, firstMs); nextCheck = now + firstMs
    }
    private var nextCheck = Long.MAX_VALUE

    /** Starts or stops [keepChecking] as the settings say. */
    private fun armChecks() {
        main.removeCallbacks(keepChecking); nextCheck = Long.MAX_VALUE
        if (checking()) stir(ColorWatch.CHECK_FAST_MS)
    }

    /**
     * A reading, or null for none (the screenshot failed, or the bar was caught fading and had nothing
     * opaque to read). No reading keeps the colours the strip has; after three in a row the strip uses
     * the theme and wallpaper hints rather than a reading of how the bar looked before. [watch] says
     * whether another reading follows.
     */
    private fun onSample(c: BarColors?, check: Boolean = false) {
        val result = ColorWatch.result(sampled, c)
        if (result == ColorWatch.Result.CHANGED) { sampled = c; applyLook(); stirred = SystemClock.uptimeMillis() }
        // The strip left the screen while the screenshot was on its way: its return asks again.
        if (!strip.shown) return
        // A check that saw the same, or nothing (the next check comes anyway, and three of them mustn't make the
        // strip guess from the wallpaper): nothing to do or log. A change is followed up as any reading's is.
        if (check && result != ColorWatch.Result.CHANGED) return
        if (watch.read(SystemClock.uptimeMillis(), result)) {
            // With checks running, a bar read well before keeps its colours: a guess from the theme and the wallpaper was
            // the wrong colour as often as not, and the next check that reads something puts the strip right anyway.
            if (Store.config.value.keepChecking && sampled != null) Log.i(tag, "no status bar colour reading, keeping the last one")
            else { Log.i(tag, "no status bar colour reading, using theme and wallpaper hints"); sampled = null; applyLook() }
        }
        arm()
        // Every reading is logged: a strip in the wrong colour can then be traced in a device's log.
        Log.i(tag, "read${if (check) " (check)" else ""} ${c?.let { "text=${it.text?.let(::hex)} bg=${it.background?.let(::hex)}" } ?: "nothing"} (${result.name.lowercase()}), " +
            (watch.due(SystemClock.uptimeMillis())?.let { "next in $it ms" } ?: "no more"))
    }

    /** The pixels around the clock (the whole bar when no clock was found), as [BarPixels] reads them. */
    private fun barColors(r: AccessibilityService.ScreenshotResult, s: BarSnapshot): BarColors? {
        val hb = r.hardwareBuffer
        val bmp = try {
            Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.let { wrapped -> wrapped.copy(Bitmap.Config.ARGB_8888, false).also { wrapped.recycle() } }
        } finally { hb.close() }
        bmp ?: return null
        try {
            val area = Rect(s.clock ?: s.bar).apply { offset(-s.bar.left, -s.bar.top) }
            // A clock box outside the picture (the bar changed size since the scan): read all of it.
            if (!area.intersect(0, 0, bmp.width, bmp.height)) area.set(0, 0, bmp.width, bmp.height)
            val px = IntArray(area.width() * area.height())
            bmp.getPixels(px, 0, area.width(), area.left, area.top, area.width(), area.height())
            return BarPixels.colors(px, area.width()) ?: null.also {
                // Why a reading gave nothing: how much of the clock box is clear, translucent and opaque.
                val a = px.map { it ushr 24 }
                Log.i(tag, "nothing to read: ${px.size} px, clear ${a.count { it < 16 }}, translucent ${a.count { it in 16..229 }}, " +
                    "opaque ${a.count { it >= 230 }}, max alpha ${a.maxOrNull()}")
            }
        } finally { bmp.recycle() }
    }

    /** No sample: a dark system theme means a dark bar on Googlebooks; otherwise the wallpaper decides. */
    private fun fallbackText(): Color {
        val night = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (night) return Color.White
        val hints = runCatching { WallpaperManager.getInstance(service).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.colorHints }.getOrNull() ?: 0
        return if (hints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0) Contrast.DARK_TEXT else Color.White
    }

    private fun applyLook() {
        val cfg = Store.config.value
        val bg = if (cfg.color.reads) sampled?.background else null
        val text = when (cfg.color) {
            ColorMode.LIGHT -> Color.White
            ColorMode.DARK -> Contrast.DARK_TEXT
            ColorMode.AUTO, ColorMode.OPPOSITE -> sampled?.text ?: bg?.let { Contrast.readableOn(it) } ?: fallbackText()
        }
        val matched = Contrast.resolve(text, bg)
        val live = if (cfg.color == ColorMode.OPPOSITE) Contrast.opposite(matched) else matched
        if (!desk) BarLook.current.value = live
        look.value = StripLook(live.fg, live.barDark, cfg.textSize, cfg.spacing.dp, cfg.pill, live.background)
        Log.i(tag, "look ${cfg.color.name.lowercase()} fg=${hex(live.fg)} bg=${bg?.let { hex(it) } ?: "transparent"} dark=${live.barDark} " +
            "contrast=${"%.1f".format(java.util.Locale.ROOT, Contrast.ratio(live.fg, live.background))}")
    }

    private fun hex(c: Color) = "#%06X".format(java.util.Locale.ROOT, c.toArgb() and 0xFFFFFF)

    // ---- the strip ---------------------------------------------------------------------------

    fun hiddenItems(): List<ItemConfig> {
        val states = Ticker.states.value
        return Store.config.value.behindChevron { states[it.id]?.active == true }
    }

    @Composable
    private fun StripHost() {
        val cfg by Store.config.collectAsState()
        val states by Ticker.states.collectAsState()
        // Items another app shows itself (items/Claims) are left out here, while that app holds them. That app draws on
        // the device's own screen only, so an external display keeps its whole strip.
        val held by com.meridian11.books.bentobar.items.Claims.claim.collectAsState()
        val claim = if (desk) com.meridian11.books.bentobar.items.ClaimRules.Claim() else held
        val entries = { list: List<ItemConfig> -> list.map { StripEntry(it, states[it.id] ?: Ticker.placeholder(it)) } }
        // Presenting (screen sharing): only what matters on stage, whatever its section.
        // While dragging, the bar shows the order the item would land in.
        val items = (dragPreview.value?.let { (id, at) -> cfg.items.moved(id, Section.SHOWN, at) } ?: cfg.items)
            .filter { com.meridian11.books.bentobar.items.ClaimRules.inStrip(it.id, claim) }
        val visible = (if (cfg.presenting) items.filter { it.section != Section.OFF && it.type in PRESENTING_TYPES && states[it.id]?.active == true }
        else items.filter { cfg.shows(it, states[it.id]?.active == true) })
            // Next meeting stays at the far left: its text changes width most, and there it moves nothing else.
            .sortedBy { if (it.type == Store.PINNED_LEFT) 0 else 1 }
        // Behind ‹ (click or hover modes): hidden items not out on their own. Show everything: none.
        val hidden = if (cfg.presenting) emptyList() else cfg.behindChevron { states[it.id]?.active == true }
            .filter { com.meridian11.books.bentobar.items.ClaimRules.inStrip(it.id, claim) }
        val overflow by this.overflow.collectAsState()
        // Without the ‹ button there's no way to fold hidden items back, so they stay folded.
        val open = expanded.value && cfg.hiddenMode != HiddenMode.SHOW_ALL && !cfg.presenting
        MeasuredStrip(bias = when (position(cfg)) { Position.RIGHT -> 1f; Position.CENTER -> 0f; Position.LEFT -> -1f },
            onWidth = { w -> if (w != strip.params.width || (w <= 0) != stripEmpty) main.post { applyWidth(w) } }) {
            // Another app shows the bar's items itself (items/Claims): nothing at all, so the strip measures empty
            // and its window shows nothing, not even its pill. Sampling goes on, as for any empty strip.
            if (claim.whole) androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier) else Strip(
                visible = entries(visible),
                revealed = if (open) entries(hidden) else emptyList(),
                // Items that don't fit are offered in the ‹ menu, so ‹ shows for them too.
                showChevron = cfg.presenting || overflow.isNotEmpty() || hidden.isNotEmpty(),
                chevronAlways = cfg.presenting || hidden.isNotEmpty(),
                chevronReservePx = (30 * density).toInt(),
                expanded = open,
                chevronOnLeft = position(cfg) != Position.LEFT,
                look = look.value,
                maxWidthPx = maxWidth.intValue,
                heightDp = heightDp.value,
                events = events,
                onOverflow = { ids -> main.post { if (this@BarController.overflow.value != ids) { this@BarController.overflow.value = ids; main.post(scanNow) } } },
                // The item a popup belongs to: "item:<id>" and "ctx:<id>" (its right-click menu) are the item's, "bentobar" is ‹'s.
                open = menuKey?.let { if (it == "bentobar") "chevron" else it.substringAfter(':', "").ifEmpty { null } },
            )
        }
    }

    /** Sizes the strip window to its content (an exact width, never WRAP_CONTENT). */
    /**
     * Nothing to draw (every item off, or only rule items that don't apply now): the strip's window
     * is made invisible rather than left up empty and a pixel wide, which is what Android's threat
     * detection looks for ("imperceptible content" from an accessibility service). Its content keeps
     * being measured, so it shows again as soon as there's something to draw.
     */
    private var stripEmpty = false

    private fun applyWidth(w: Int) {
        val empty = w <= 0
        if (empty != stripEmpty) {
            stripEmpty = empty; strip.setContentVisible(!empty); trace("strip ${if (empty) "empty" else "has content"}"); updateAwake()
            // Nothing to draw: no readings; something again: the readings owed meanwhile, or two quick ones.
            if (started && strip.shown) {
                if (empty) watch.pause() else watch.resume(SystemClock.uptimeMillis(), 250)
                arm()
            }
        }
        val width = w.coerceAtLeast(1)
        if (strip.params.width == width) return
        trace("width ${strip.params.width} -> $width")
        strip.params.width = width
        strip.relayout()
    }

    private var dragFrom: Pair<String, Rect>? = null
    private var dragCenters: Map<String, Float> = emptyMap()
    /** While an item is dragged: its id and the index it would land at, for the strip's live order. */
    private val dragPreview = mutableStateOf<Pair<String, Int>?>(null)

    /** Ends a drag without moving anything: the order drawn is the saved one again. */
    private fun cancelDrag() { dragPreview.value = null; dragFrom = null; dragCenters = emptyMap() }

    private val events = object : StripEvents {
        override fun placed(id: String, at: Rect) { placed[id] = at }

        override fun click(item: ItemConfig, at: Rect) {
            placed[item.id] = at
            // A click ends the hover's tooltip, whatever it does next (a screenshot must not catch it).
            hideTip(now = true)
            val type = Items.of(item.type) ?: return
            if (type.onClick(item)) { Ticker.refresh(); return }
            if (type.menu == null) return
            toggleMenu("item:${item.id}", at, type.menuWidthDp) { host -> ItemMenu(item.id, host) }
        }

        override fun context(item: ItemConfig, at: Rect) {
            placed[item.id] = at
            toggleMenu("ctx:${item.id}", at, 280) { host ->
                ItemContextMenu(item.id, host) {
                    val type = Items.of(item.type)
                    if (type?.menu != null) { closeMenu(dissolve = true); toggleMenu("item:${item.id}", at, type.menuWidthDp) { h -> ItemMenu(item.id, h) } }
                }
            }
        }

        override fun scroll(item: ItemConfig, steps: Int) {
            Items.of(item.type)?.onScroll(item, steps)
            Ticker.refresh()
        }

        /** The slider in the bar: the item sets what it stands for, and only that item is drawn again (no sampler runs for a drag). */
        override fun slide(item: ItemConfig, level: Float, done: Boolean) {
            sliding = !done
            hideTip()
            Items.of(item.type)?.onSlide(item, level, done)
            Ticker.refresh(item)
        }

        override fun chevron(at: Rect) {
            // While presenting, or with nothing behind it (show everything, or ‹ only for items that
            // don't fit), ‹ opens the menu.
            Store.config.value.let { if (it.presenting || it.hiddenMode == HiddenMode.SHOW_ALL || (!expanded.value && hiddenItems().isEmpty())) return chevronContext(at) }
            val next = !expanded.value
            expanded.value = next
            pinned = next
            if (Store.config.value.pinnedOpen != next) Store.update { it.copy(pinnedOpen = next) }
            main.removeCallbacks(collapse)
            val secs = Store.config.value.autoCollapseSec
            if (next && secs > 0) main.postDelayed(collapse, secs * 1000L)
        }

        override fun chevronContext(at: Rect) = barMenu(at, everything = false)

        /**
         * A drag in the bar. While it moves, the strip previews the new order ([dragPreview]), so
         * the other items slide aside; on release that order is saved. The landing place is judged
         * against where the items were when the drag began (a snapshot), not their moving slots,
         * so it can't flip back and forth at a boundary. Only items in the bar move this way;
         * ones out on their own or revealed from ‹ keep their section.
         */
        override fun drag(item: ItemConfig, dx: Float, done: Boolean) {
            hideTip()
            if (dragFrom?.first != item.id) {
                val r = placed[item.id]
                dragFrom = r?.let { item.id to Rect(it) }
                dragCenters = placed.filterKeys { it != item.id }.mapValues { it.value.exactCenterX() }
            }
            val from = dragFrom?.second
            val cfg = Store.config.value
            val index = if (item.section != Section.SHOWN || from == null) null else {
                val center = from.exactCenterX() + dx
                val overflow = this@BarController.overflow.value
                val others = cfg.items.filter { it.section == Section.SHOWN && it.id != item.id }
                val drawn = others.filter { it.id !in overflow && dragCenters.containsKey(it.id) }
                val after = drawn.firstOrNull { dragCenters.getValue(it.id) > center }
                if (after == null) others.size else others.indexOfFirst { it.id == after.id }
            }
            if (!done) {
                if (index != null && dragPreview.value != (item.id to index)) dragPreview.value = item.id to index
                return
            }
            trace("drag ${item.type} dx=${dx.toInt()} -> index $index")
            if (index != null) Store.move(item.id, Section.SHOWN, index)
            cancelDrag()
        }

        override fun gapClick(key: String) {
            val at = placed[key] ?: return
            if (key == "chevron") { chevron(at); return }
            click(Store.config.value.items.firstOrNull { it.id == key } ?: return, at)
        }

        override fun wheel(up: Boolean) {
            val cfg = Store.config.value
            if (cfg.presenting || cfg.hiddenMode == HiddenMode.SHOW_ALL || hiddenItems().isEmpty()) return
            if (up != expanded.value) { expanded.value = up; pinned = false }
        }

        override fun itemHover(item: ItemConfig, at: Rect, inside: Boolean) {
            main.removeCallbacks(showTip)
            // "Switch popups on hover": with a popup open, the item pointed at opens its own, as in a menu bar.
            val type = Items.of(item.type)
            if (inside && !sliding && type != null &&
                PopupRules.hoverOpens(menuKey, item.id, hasPopup = type.menu != null, on = Store.config.value.hoverSwitchesPopups)) {
                placed[item.id] = at
                toggleMenu("item:${item.id}", at, type.menuWidthDp) { host -> ItemMenu(item.id, host) }
                return
            }
            if (inside && menu == null && !sliding) { tipFor = item to Rect(at); main.postDelayed(showTip, 600) } else hideTip()
        }

        override fun hover(inside: Boolean) {
            trace("hover $inside")
            hovering = inside
            main.removeCallbacks(expandOnHover); main.removeCallbacks(collapse)
            if (inside) {
                if (Store.config.value.hiddenMode == HiddenMode.HOVER && !expanded.value && hiddenItems().isNotEmpty()) main.postDelayed(expandOnHover, 350)
            } else if (!pinned) main.postDelayed(collapse, 900)
            else {
                val secs = Store.config.value.autoCollapseSec
                if (secs > 0) main.postDelayed(collapse, secs * 1000L)
            }
        }
    }

    private fun barMenu(at: Rect, everything: Boolean) = toggleMenu("bentobar", at, 290) { host ->
        BentoBarMenu(host, openItem = { item ->
            val type = Items.of(item.type)
            closeMenu(dissolve = true)
            // From the list of every item, an item's menu opens under the item itself.
            val anchor = placed[item.id]?.takeIf { everything } ?: at
            if (type != null && type.onClick(item)) Ticker.refresh()
            else if (type?.menu != null) toggleMenu("item:${item.id}", anchor, type.menuWidthDp) { h -> ItemMenu(item.id, h) }
        }, hideBar = { Store.update { it.copy(enabled = false) } }, everything = everything, overflowIds = overflow)
    }

    /**
     * The "BentoBar menu" entry (a keyboard shortcut the user binds in the system's shortcut
     * settings): BentoBar's menu listing every item, focused for arrows and Enter. Again closes it.
     * False when the strip isn't showing.
     */
    fun openBarMenu(): Boolean {
        if (!started || !strip.shown) return false
        // Under the strip's end next to the system icons (its start when the strip is on the left).
        val w = strip.params.width
        val h = strip.params.height
        val at = if (position(Store.config.value) == Position.LEFT) Rect(0, 0, 1, h) else Rect(w - 1, 0, w, h)
        barMenu(at, everything = true)
        return true
    }

    // ---- menus -------------------------------------------------------------------------------

    private val host = object : MenuHost {
        override fun close() = closeMenu()
        override fun openItemSettings(id: String) { closeMenu(); MainActivity.open(service, id.ifEmpty { null }) }
        // Once the popup is gone from the screen (a screenshot must not show it): a frame after its window is removed.
        override fun afterClose(action: () -> Unit) { closeMenu(then = { main.postDelayed(action, 50) }) }
    }

    /**
     * Another app that shows the bar's items (items/ItemsProvider) asks for what a click on [id] does, with the popup
     * under [onScreen], a place of its own in screen coordinates: the item's own click, else its popup there (a
     * second ask closes it, as a second click does). False where there is no such item or it has nothing to open.
     * [hover]: the pointer rests on the item there: only its popup, which stays if it's already open.
     */
    fun openAt(id: String, onScreen: Rect, hover: Boolean = false): Boolean {
        val item = Store.config.value.items.firstOrNull { it.id == id } ?: return false
        val type = Items.of(item.type) ?: return false
        if (hover && (type.menu == null || menuKey == "item:${item.id}")) return type.menu != null
        hideTip(now = true)
        if (!hover && type.onClick(item)) { Ticker.refresh(); return true }
        if (type.menu == null) return false
        val loc = strip.locationOnScreen()
        sharedKey = "item:${item.id}"
        toggleMenu("item:${item.id}", Rect(onScreen).apply { offset(-loc[0], -loc[1]) }, type.menuWidthDp, anywhere = true) { host -> ItemMenu(item.id, host) }
        return true
    }

    /** The popup last opened for another app ([openAt]), while it may still be open. */
    private var sharedKey: String? = null

    /**
     * That app moved on (its own panel opened, say): the popup it asked for goes. One the user opened from the bar
     * itself since then stays.
     */
    fun closeShared() {
        val key = sharedKey ?: return
        sharedKey = null
        if (menuKey == key) closeMenu()
    }

    /**
     * Opens the popup [key] below [anchor] (strip window coordinates), or closes it if it's open. A popup
     * open for another item dissolves while this one unfolds; one still folding away turns round.
     */
    fun toggleMenu(key: String, anchor: Rect, widthDp: Int, anywhere: Boolean = false, content: @Composable (MenuHost) -> Unit) {
        val now = SystemClock.uptimeMillis()
        hideTip(now = true)
        if (menuKey == key) { closeMenu(); return }
        if (spentKey == key) { spentKey = null; if (now - spentAt < 1000) return }
        spentKey = null
        val back = leaving
        if (menu == null && !leavingThen && leavingKey == key && back != null && back.reopen()) {
            leaving = null; leavingKey = null
            menu = back; menuKey = key
            focusMenu(key)
            return
        }
        closeMenu(dissolve = true) // resets the sampling demand, so the new popup's is set after it
        val s = snap ?: return
        val loc = strip.locationOnScreen()
        val a = Rect(anchor).apply { offset(loc[0], loc[1]) }
        val bounds = screenBounds()
        val w = (widthDp * density).toInt()
        val side = (MenuRoom.side.value * density).toInt()
        // Lined up with its item, and kept within the strip; or within the screen, for an item another app shows
        // somewhere of its own ([anywhere], openAt).
        val x = if (anywhere) PopupRules.popupLeft(a.left, a.right, w, 0, bounds.width(), bounds.width(), side)
            else PopupRules.popupLeft(a.left, a.right, w, loc[0], loc[0] + strip.params.width, bounds.width(), side)
        // On a taskbar, the status bar's popup mirrored: its bottom edge as far above the taskbar as a popup's top edge is
        // below the status bar, and it unfolds up from there.
        val gap = (MenuRoom.top.value * density).toInt()
        val y = if (desk) s.bar.top - gap else s.bar.bottom + gap
        val maxH = ((if (desk) s.bar.top else bounds.height() - s.bar.bottom) / density - 170).toInt().coerceAtLeast(200)
        val o = MenuWindow(ctx, "BentoBar menu", onOutside = { outside(it) }, onEscape = { closeMenu() })
        handOff?.let { old -> o.onMove = { if (handOff === old) dissolveHandOff() } }
        if (!o.show(x, y, w, above = desk, screenHeight = bounds.height(), shadow = { MenuShadow(widthDp.dp, o.glass) }) { MenuSurface(widthDp.dp, maxH.dp, o.glass) { content(host) } }) return
        menu = o
        menuKey = key
        focusMenu(key)
    }

    /**
     * A press outside the open popup. On the popup's own item it folds the popup away and that press's click is
     * spent; on another item the popup dissolves, and the click opens that item's popup as it unfolds; anywhere
     * else it folds. (A press on another app's window comes without coordinates: it is "anywhere else".)
     */
    private fun outside(ev: MotionEvent) {
        val key = menuKey ?: return
        val loc = strip.locationOnScreen()
        val x = ev.rawX - loc[0]
        val y = ev.rawY - loc[1]
        val onStrip = (ev.rawX != 0f || ev.rawY != 0f) && strip.shown &&
            x >= 0 && x < strip.params.width && y >= 0 && y < (snap?.bar?.height() ?: 0)
        // The same rule as the highlight's: a press in a gap belongs to the item on its side of the gap's middle.
        val hit = if (onStrip) StripHighlight.target(x, placed.map { (k, r) -> Span(k, r.left.toFloat(), r.right.toFloat()) }.sortedBy { it.left }, null)
            else null
        val own = if (key == "bentobar") "chevron" else key.substringAfter(':')
        when (hit) {
            null -> closeMenu()
            own -> { spentKey = key; spentAt = SystemClock.uptimeMillis(); closeMenu() }
            else -> closeMenu(dissolve = true)
        }
    }

    private fun focusMenu(key: String) {
        Ticker.focusItem = key.removePrefix("item:").takeIf { key.startsWith("item:") }
        Ticker.revealHidden = expanded.value || key == "bentobar"
        Ticker.start("menu")
    }

    // ---- tooltips ----------------------------------------------------------------------------

    private var tip: MenuWindow? = null
    private var tipFor: Pair<ItemConfig, Rect>? = null
    private val showTip = Runnable { tipFor?.let { (item, at) -> showTooltip(item, at) } }

    /**
     * The item's name below it, after a short hover: icon-only items otherwise give a mouse user
     * nothing to go on. An item whose state has a tooltip of its own (a track's whole title and
     * artist, which flight this is) shows that instead. A no-touch window, so it never takes a click.
     */
    private fun showTooltip(item: ItemConfig, anchor: Rect) {
        hideTip(now = true)
        val s = snap ?: return
        if (menu != null || !strip.shown) return
        val label = Ticker.states.value[item.id]?.tooltip?.takeIf { it.isNotBlank() } ?: Items.of(item.type)?.title ?: return
        val loc = strip.locationOnScreen()
        val a = Rect(anchor).apply { offset(loc[0], loc[1]) }
        val paint = android.text.TextPaint().apply { textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 13f, service.resources.displayMetrics) }
        // A long tip ends in an ellipsis: the window is never wider than this.
        // The glass sets the text a little wider than this paint measures it (its letter spacing): room for that.
        val w = ((paint.measureText(label) * 1.06f + 2 * 10 * density).toInt() + (6 * density).toInt()).coerceAtMost((TOOLTIP_MAX_DP * density).toInt())
        val bounds = screenBounds()
        // On the popups' glass, and unfolding as they do (up from a taskbar): a small popup that takes no touch.
        val o = MenuWindow(ctx, "BentoBar tooltip", onOutside = {}, onEscape = {})
        val widthDp = (w / density).toInt() + 1
        val x = (a.centerX() - w / 2).coerceIn(0, (bounds.width() - w).coerceAtLeast(0))
        val gap = (4 * density).toInt()
        val y = if (desk) s.bar.top - gap else s.bar.bottom + gap
        if (!o.show(x, y, (widthDp * density).toInt(), above = desk, screenHeight = bounds.height(), passive = true,
                shadow = { MenuShadow(widthDp.dp, o.glass) }) { MenuSurface(widthDp.dp, TOOLTIP_HEIGHT, o.glass) { Tooltip(label) } }) return
        tip = o
    }

    /** The tooltip goes: it fades where it is, or at once ([now]: a click, a popup opening, a screenshot to come). */
    private fun hideTip(now: Boolean = false) {
        main.removeCallbacks(showTip)
        tip?.close(dissolve = true, now = now); tip = null
    }

    /**
     * Closes the open popup: it folds back into its item ([dissolve]: it dissolves in place, left for
     * another), or goes at once ([now]: the service stopping, the screen going off, the display
     * changing). [then] runs once it is off the screen.
     */
    fun closeMenu(now: Boolean = false, dissolve: Boolean = false, then: (() -> Unit)? = null) {
        if (now) dropHandOff.run()
        val o = menu
        if (o == null) {
            if (now) { leaving?.close(now = true); leaving = null; leavingKey = null; leavingThen = false }
            then?.invoke()
            return
        }
        menu = null
        // An earlier one still leaving goes at once: only one popup is ever folding away.
        leaving?.close(now = true)
        if (desk && dissolve && !now && then == null) {
            // On a taskbar a popup left for another stays as it is, taking no touches, until the new one starts to rise, and
            // dissolves from then on, as on the status bar: dissolving at once, it was half gone before the new one moved
            // (that waits for its first frames); going at once, there was a moment with neither, and the popup seemed to bounce.
            dropHandOff.run()
            o.stopTouches()
            handOff = o
            main.postDelayed(dropHandOff, HANDOFF_MAX_MS)
        } else {
            leaving = o; leavingKey = menuKey; leavingThen = then != null
            o.close(dissolve = dissolve, now = now) {
                if (leaving === o) { leaving = null; leavingKey = null; leavingThen = false }
                then?.invoke()
            }
        }
        menuKey = null
        Ticker.focusItem = null
        Ticker.revealHidden = expanded.value
        Ticker.stop("menu")
        if (!hovering && !pinned) { main.removeCallbacks(collapse); main.postDelayed(collapse, 600) }
    }

    // ---- keep awake --------------------------------------------------------------------------

    /**
     * Keep awake holds the screen on through the strip's own window (FLAG_KEEP_SCREEN_ON on a
     * window that's visible), not a window of its own. It used to be a separate 1×1 see-through
     * overlay: Play Protect's live threat detection flags an accessibility service that keeps
     * "imperceptible content" on screen ("App displays over other apps"). The catch: while the strip
     * is hidden (a full-screen app, the screen locked), keep awake waits; full-screen video players
     * keep the screen on themselves.
     */
    private fun updateAwake() {
        if (desk) return // the device's own strip keeps the screen on
        val on = Caffeine.active() || DisplaysItem.keepingAwake.value
        val f = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val flags = if (on) strip.params.flags or f else strip.params.flags and f.inv()
        if (flags != strip.params.flags) {
            strip.params.flags = flags
            strip.relayout()
            Log.i(tag, if (on) "keep awake on" else "keep awake off")
        }
        // While the strip is hidden (a full-screen app, BentoBar hidden from its tile) or has nothing
        // to draw, no visible window of ours carries the flag: a screen wake lock holds it instead,
        // with no window. Released when the strip is back, keep awake ends, the screen goes off (the
        // power key or the lid) or the lock screen is up.
        val lock = on && (!strip.shown || stripEmpty) && pm.isInteractive && !km.isKeyguardLocked
        if (lock && !screenLock.isHeld) { screenLock.acquire(); Log.i(tag, "keep awake: wake lock while the bar is hidden") }
        else if (!lock && screenLock.isHeld) screenLock.release()
    }

    /** See [updateAwake]. Deprecated in favor of FLAG_KEEP_SCREEN_ON, which needs a visible window. */
    @Suppress("DEPRECATION")
    private val screenLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "BentoBar:keepAwake").apply { setReferenceCounted(false) }

    // ---- tests (adb only) --------------------------------------------------------------------

    fun debug(cmd: List<String>): String {
        fun first(type: String) = Store.config.value.items.firstOrNull { it.type == type || it.id == type }
        return when (cmd.firstOrNull()) {
            "dump" -> "snap=$snap look=${look.value} expanded=${expanded.value} menu=$menuKey shown=${strip.shown} placed=$placed"
            "open", "ctx" -> {
                val item = first(cmd.getOrElse(1) { "" }) ?: return "no such item"
                val at = placed[item.id] ?: return "item not drawn yet"
                if (cmd[0] == "open") events.click(item, at) else events.context(item, at); "ok"
            }
            "tip" -> {
                val item = first(cmd.getOrElse(1) { "" }) ?: return "no such item"
                val at = placed[item.id] ?: return "item not drawn yet"
                if (cmd.getOrNull(2) == "off") hideTip() else showTooltip(item, at); "ok"
            }
            "chevron" -> { events.chevron(Rect()); "expanded=${expanded.value}" }
            "scroll" -> {
                val item = first(cmd.getOrElse(1) { "" }) ?: return "no such item"
                events.scroll(item, cmd.getOrNull(2)?.toIntOrNull() ?: 1); "ok"
            }
            "barmenu" -> { events.chevronContext(placed["chevron"] ?: Rect(0, 0, 40, 40)); "ok" }
            "hover" -> { events.hover(cmd.getOrNull(1) == "on"); "ok" }
            "close" -> { closeMenu(); "ok" }
            "solid" -> { MenuWindow.solid = cmd.getOrNull(1) != "off"; "solid=${MenuWindow.solid}" }
            "scan" -> { scan(); "snap=$snap" }
            "windows" -> service.windows.joinToString(" | ") { w ->
                val r = Rect().also { w.getBoundsInScreen(it) }
                "${w.id}:t${w.type}:${w.title}:${r.toShortString()}"
            }
            "alldisplays" -> runCatching { service.windowsOnAllDisplays }.getOrNull()?.let { all ->
                (0 until all.size()).joinToString(" || ") { i ->
                    "d${all.keyAt(i)}: " + all.valueAt(i).joinToString(" | ") { w ->
                        val r = Rect().also { w.getBoundsInScreen(it) }
                        "${w.id}:t${w.type}:${w.title}:${r.toShortString()}"
                    }
                }
            } ?: "none"
            "tree" -> {
                val id = cmd.getOrNull(1)?.toIntOrNull()
                val w = runCatching { service.windowsOnAllDisplays }.getOrNull()?.let { all -> (0 until all.size()).flatMap { all.valueAt(it) } }
                    ?.firstOrNull { it.id == id } ?: return "no such window"
                val out = StringBuilder()
                fun walk(n: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
                    if (n == null || depth > 12) return
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    out.append(" ¦").append("-".repeat(depth)).append(n.className?.toString()?.substringAfterLast('.')).append(' ')
                        .append(n.viewIdResourceName?.substringAfter(":id/") ?: "").append(' ').append(r.toShortString())
                        .append(if (n.contentDescription != null) " cd" else "").append(if (n.isClickable) " click" else "")
                    for (c in 0 until n.childCount) walk(n.getChild(c), depth + 1)
                }
                walk(w.root, 0); out.toString()
            }
            "sample" -> { sampleColor(); "sampling" }
            "semblur" -> {
                SemBlur.enabled = cmd.getOrNull(1) != "off"
                "semblur=${SemBlur.wanted} available=${SemBlur.available}"
            }
            "batteryprobe" -> service.getSystemService(android.os.BatteryManager::class.java).let { bm ->
                (6..12).joinToString(" ") { "$it=${runCatching { bm.getIntProperty(it) }.getOrElse { e -> e.javaClass.simpleName }}" }
            }
            "batteryextras" -> service.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.extras?.let { x ->
                x.keySet().sorted().joinToString(" ") { k -> "$k=${@Suppress("DEPRECATION") x.get(k)}" }
            } ?: "none"
            "trace" -> { tracing = cmd.getOrNull(1) != "off"; "trace=$tracing" }
            else -> "unknown"
        }
    }

    companion object {
        private const val TOOLTIP_MAX_DP = 360
        /** A popup left for another on a taskbar goes after this long anyway, if the new one never starts to rise. */
        private const val HANDOFF_MAX_MS = 400L
        /** A desktop taskbar is no taller than this (DeX's is about 26 dp). */
        private const val TASKBAR_MAX_DP = 96
        /** Another window took the focus or became the active one: usually another app in front. */
        private const val SWITCHED = AccessibilityEvent.WINDOWS_CHANGE_FOCUSED or AccessibilityEvent.WINDOWS_CHANGE_ACTIVE
        private const val RELEVANT = AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_REMOVED or
            AccessibilityEvent.WINDOWS_CHANGE_BOUNDS
    }
}
