package com.meridian11.books.bentobar.bar

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.meridian11.books.bentobar.data.Store
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.ui.MenuGlassState
import com.meridian11.books.bentobar.ui.MenuMotion
import com.meridian11.books.bentobar.ui.MenuRoom
import com.meridian11.books.bentobar.ui.Motion
import java.util.function.Consumer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A popup's window (docs/design/1.3/engineering.md). Unlike the strip's [Overlay] it is a dialog's
 * window: only a `Window` can blur what is behind it, and a dialog of type TYPE_ACCESSIBILITY_OVERLAY
 * made with the service's context gets the service's token from its WindowManager, as `addView` does.
 *
 * The window is exactly the card, added at its full size and never resized while it moves: the card
 * unfolds inside it. Before each frame its root view is cut to the glass as far as it shows (its bottom
 * edge only), because the platform blurs exactly the root view's rectangle (Booklight's "the blur
 * follows the glass"). Only the bottom moves, so what is drawn and where touches land stay put: Compose
 * places input by the root view's own top-left corner. The shadow, which lies outside the card, is drawn
 * in a window of its own just below ([MenuRoom] around the card).
 *
 * One clock drives everything ([MenuMotion]), from the popup's first drawn frame: the glass, its blur
 * and shadow (read by the Compose content from [glass]), and its blocks. Closing folds the glass back
 * into its item; a timer takes the window away regardless, so a stalled clock (the screen going off)
 * can never leave one up.
 */
class MenuWindow(
    private val service: Context,
    private val title: String,
    /** A press outside the popup (its coordinates are on screen only where it lands on one of BentoBar's own windows). */
    private val onOutside: (MotionEvent) -> Unit,
    private val onEscape: () -> Unit,
) {
    val glass = MenuGlassState()

    private val wm = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val outline = GlassOutline()
    private val models = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    /** The shadow's window: never touched, never focused, just below the popup's. */
    private val shade = Overlay(service, "$title shadow", touchable = false)

    private val dialog = object : ComponentDialog(service, R.style.Theme_BentoBar_Menu) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (event.action == KeyEvent.ACTION_UP) onEscape()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { onOutside(ev); return true }
            return super.dispatchTouchEvent(ev)
        }
    }

    /** The room around the card in the shadow's window, in px. */
    private val roomSide = (MenuRoom.side.value * density).roundToInt()
    private val roomTop = (MenuRoom.top.value * density).roundToInt()
    private val roomBottom = (MenuRoom.bottom.value * density).roundToInt()
    private val blurPx = (BLUR_DP * density).roundToInt()

    private enum class Phase { OPENING, OPEN, CLOSING, REOPENING, DISSOLVING, GONE }

    private var phase = Phase.OPENING
    /** The frame time the phase began, in ns; -1 until its first frame. */
    private var phaseStart = -1L
    /**
     * The phase's own clock, in ms of motion: it advances by the time between frames, but never by more than one and
     * a half frames at once. A late frame (a part's first drawing) then slows the motion for a moment instead of
     * skipping ahead: the glass never sits still and then leaps (the motion review of v2).
     */
    private var motionMs = 0f
    private var lastStep = -1L
    private var closeFrom = 0f
    private var closePresence = 1f
    private var reopenFrom = 0f
    private var reopenSpeed = 0f
    private var reopenPresence = 1f
    private var reopenContents = 1f
    /** The glass's height in the last two frames and their times, for its speed when it turns round. */
    private var lastDp = 0f
    private var lastNanos = 0L
    private var nowDp = 0f
    private var nowNanos = 0L
    private var framed = false
    /** The blur's corner radius last given, in px. */
    private var lastCorner = -1f
    private var lastBlur = -1
    private var onGone: (() -> Unit)? = null
    private var shown = false
    /** Standing on a bar at the bottom: the glass shows from its bottom edge up. */
    private var above = false
    /**
     * The window's first frame has gone to the screen. A new window's first frame takes a while (tens of ms):
     * the opening's clock starts after it, or the glass would be part way open before anything showed.
     */
    private var committed = false
    /** When the first frame was asked for, in ns: the clock starts anyway if Android never says (a window not drawn). */
    private var firstAsked = -1L
    /** The last two frame times while the opening waits, in ns: it starts once frames come on time. */
    private var tick1 = -1L
    private var tick2 = -1L

    private val blurListener = Consumer<Boolean> { on ->
        glass.blur = (on || SemBlur.wanted) && !solid; glass.samsung = glass.blur && !on && SemBlur.wanted; applyBlur()
    }
    private val frameGlass = ViewTreeObserver.OnPreDrawListener { frame(); true }
    private val tick = Choreographer.FrameCallback { step(it) }
    private val removal = Runnable { remove() }

    /**
     * Runs once, when the opening starts to move (its first frames on the screen), or at once without animations:
     * the popup this one replaces can go then, with no moment between them where neither shows.
     */
    var onMove: (() -> Unit)? = null

    /** Takes no more touches or keys, while it stays as it is (left for another popup, until that one moves). */
    fun stopTouches() = passThrough()

    /** True while the popup is up or still leaving. */
    val isShowing get() = shown

    /**
     * Shows the popup with its card's top-left corner at ([x], [y]) on screen, [width] px wide. False if
     * Android refused the window (the service is going): nothing is up then. [above]: the popup stands on a bar at the
     * bottom of the screen instead (a desktop taskbar), its card's bottom edge at [y] on a screen [screenHeight] px tall.
     * It unfolds up from there: the status bar's popup mirrored.
     */
    fun show(x: Int, y: Int, width: Int, above: Boolean = false, screenHeight: Int = 0, passive: Boolean = false,
        shadow: @Composable () -> Unit, content: @Composable () -> Unit): Boolean {
        this.above = above
        glass.fromBottom = above
        val vertical = if (above) Gravity.BOTTOM else Gravity.TOP
        val window = dialog.window ?: return false
        glass.blur = (wm.isCrossWindowBlurEnabled || SemBlur.wanted) && !solid
        glass.samsung = glass.blur && !wm.isCrossWindowBlurEnabled && SemBlur.wanted
        dialog.setCancelable(false)
        dialog.setTitle(title)
        dialog.setContentView(ComposeView(dialog.context).apply { setContent(content) })
        window.decorView.setViewTreeViewModelStoreOwner(models)
        window.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        window.setBackgroundDrawable(outline)
        window.setWindowAnimations(0)
        window.setGravity(vertical or Gravity.LEFT)
        window.setDecorFitsSystemWindows(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                // A tooltip ([passive]) takes no touch, no key and no focus: what is under it gets them.
                (if (passive) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                else WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH),
        )
        window.attributes = window.attributes.apply {
            this.x = x
            // Rising: the window stands on the bar, its bottom edge [y] from the top, so this far from the bottom.
            this.y = if (above) screenHeight - y else y
            this.width = width
            height = WindowManager.LayoutParams.WRAP_CONTENT
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setTitle(title)
        }
        // Asked for now, not with the first frame: the platform turns the blur on in a message of its own, which would
        // wait behind the opening's frames. Never 0 on the way in, or the platform lets the blur layer go.
        applyBlur()
        window.decorView.viewTreeObserver.addOnPreDrawListener(frameGlass)
        // The shadow's window first, so the popup's own is above it (later windows of one type are higher).
        shade.params.gravity = (if (above) Gravity.BOTTOM else Gravity.TOP) or Gravity.LEFT
        shade.params.x = x - roomSide
        shade.params.y = if (above) screenHeight - y - roomTop else y - roomTop
        shade.params.width = width + 2 * roomSide
        shade.show(shadow)
        try {
            dialog.show()
        } catch (e: RuntimeException) {
            Log.w("BentoBar", "can't add the window $title: ${e.javaClass.simpleName}")
            window.decorView.viewTreeObserver.removeOnPreDrawListener(frameGlass)
            shade.destroy()
            return false
        }
        shown = true
        wm.addCrossWindowBlurEnabledListener(service.mainExecutor, blurListener)
        committed = false
        firstAsked = -1L
        tick1 = -1L; tick2 = -1L
        window.decorView.viewTreeObserver.registerFrameCommitCallback { committed = true }
        phase = Phase.OPENING
        phaseStart = -1L
        if (scale() <= 0f) { settle(); moved() } else Choreographer.getInstance().postFrameCallback(tick)
        return true
    }

    /**
     * Closes the popup: it takes no more touches or keys from this moment (the next click goes where it
     * points), folds back into its item and is taken away; [gone] runs then. [dissolve]: it was left for
     * another popup, and dissolves in place instead of folding. [now]: away at once (the service stopping,
     * the display changing).
     */
    fun close(dissolve: Boolean = false, now: Boolean = false, gone: (() -> Unit)? = null) {
        if (!shown) { gone?.invoke(); return }
        // Closed again at once (the screen going off mid-fold): what was to run after the first close still runs.
        if (gone != null) onGone = gone
        if (now || scale() <= 0f) { remove(); return }
        if (phase == Phase.CLOSING || phase == Phase.DISSOLVING) return
        passThrough()
        if (above) { glass.shadow = 0f; sync() }
        closeFrom = glass.heightDp
        closePresence = glass.presence
        phase = if (dissolve) Phase.DISSOLVING else Phase.CLOSING
        phaseStart = -1L
        val ms = if (dissolve) MenuMotion.DISSOLVE_MS else MenuMotion.closeMs(closeFrom, fullDp())
        main.removeCallbacks(removal)
        main.postDelayed(removal, (ms * scale()).toLong() + REMOVAL_GRACE_MS)
        Choreographer.getInstance().removeFrameCallback(tick)
        Choreographer.getInstance().postFrameCallback(tick)
    }

    /** Asked to open again while it folds away: it turns round from where it is, keeping its speed. False if it can't (dissolving, gone). */
    fun reopen(): Boolean {
        if (!shown || phase != Phase.CLOSING) return false
        main.removeCallbacks(removal)
        onGone = null
        takeTouches()
        reopenFrom = glass.heightDp
        reopenSpeed = speed()
        // Blocks that hadn't come in yet are simply there: the contents fade back in as a whole.
        glass.clockMs = Float.POSITIVE_INFINITY
        reopenPresence = glass.presence
        reopenContents = glass.contents
        phase = Phase.REOPENING
        phaseStart = -1L
        return true
    }

    private fun moved() { val m = onMove; onMove = null; m?.invoke() }

    private fun step(nanos: Long) {
        if (!shown || phase == Phase.GONE || phase == Phase.OPEN) return
        val full = fullDp()
        if (full <= 0f) { Choreographer.getInstance().postFrameCallback(tick); return } // not measured yet
        if (phase == Phase.OPENING && phaseStart < 0) {
            if (firstAsked < 0) firstAsked = nanos
            // Until the first frame is on its way to the screen, and frames come on time: the lip, not there yet; the clock
            // waits. A new window's first frames are slow (laying out the whole popup, the shadow's window taking its size):
            // started on the first of them, the glass sat still, half there, and then jumped to catch up.
            val frame = 1_000_000_000L / refresh()
            val onTime = tick1 > 0 && tick2 > 0 && nanos - tick1 <= frame * 3 / 2 && tick1 - tick2 <= frame * 3 / 2
            tick2 = tick1; tick1 = nanos
            if (!(committed && onTime) && nanos - firstAsked < FIRST_FRAME_WAIT_NS) {
                put(MenuMotion.opening(0f, full), nanos)
                Choreographer.getInstance().postFrameCallback(tick)
                return
            }
        }
        if (phaseStart < 0) {
            phaseStart = nanos; motionMs = 0f; lastStep = nanos
            if (phase == Phase.OPENING) moved()
        }
        val frameMs = 1000f / refresh()
        val stepMs = ((nanos - lastStep) / 1e6f).coerceIn(0f, 1.5f * frameMs)
        lastStep = nanos
        motionMs += stepMs
        val ms = MenuMotion.scaled(motionMs, scale())
        when (phase) {
            Phase.OPENING -> {
                val g = MenuMotion.opening(ms, full)
                if (Log.isLoggable(MOTION_TAG, Log.DEBUG)) Log.d(MOTION_TAG, "open ms=${ms.roundToInt()} h=${g.heightDp.roundToInt()}/${full.roundToInt()} p=${"%.2f".format(g.presence)} step=${"%.1f".format(stepMs)}")
                put(g, nanos)
                glass.clockMs = ms
                sync()
                if (g.done && ms >= MenuMotion.blocksDoneMs(9)) { settle(); return }
            }
            Phase.REOPENING -> {
                val g = MenuMotion.reopening(ms, reopenFrom, reopenSpeed, full, reopenPresence, reopenContents)
                put(g, nanos)
                if (g.done) { settle(); return }
            }
            Phase.CLOSING -> {
                val g = MenuMotion.closing(ms, closeFrom, full, closePresence)
                put(g, nanos)
                if (g.done) { remove(); return }
            }
            Phase.DISSOLVING -> {
                glass.alpha = MenuMotion.dissolve(ms)
                applyBlur()
                sync()
                if (ms >= MenuMotion.DISSOLVE_MS) { remove(); return }
            }
            else -> return
        }
        Choreographer.getInstance().postFrameCallback(tick)
    }

    private fun put(g: MenuMotion.Glass, nanos: Long) {
        lastDp = nowDp; lastNanos = nowNanos
        nowDp = g.heightDp; nowNanos = nanos
        glass.heightDp = g.heightDp
        glass.presence = g.presence
        // Standing on a taskbar, no shadow while it moves: above the rising or folding edge it showed as a haze sticking
        // out of the glass. It comes in with the frost once the popup rests ([frostIn]).
        glass.shadow = if (above) 0f else g.shadow
        glass.contents = g.contents
        applyBlur()
        sync()
    }

    /**
     * Tells Compose of this frame's values now. Written outside a composition, they would otherwise reach it with its
     * next frame, while the blur's region (framed before this frame is drawn) already has them: the blur ran a frame
     * ahead of the glass, a frosted band below its edge during the fast middle of the opening.
     */
    private fun sync() = androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()

    /** At rest, open: the glass whole, the blocks in place, the clock stopped. */
    private fun settle() {
        phase = Phase.OPEN
        if (above) { restedAt = SystemClock.uptimeMillis(); Choreographer.getInstance().postFrameCallback(frostIn) }
        glass.heightDp = fullDp().coerceAtLeast(MenuMotion.LIP_DP)
        glass.presence = 1f; glass.shadow = if (above) 0f else 1f; glass.contents = 1f; glass.alpha = 1f
        glass.clockMs = Float.POSITIVE_INFINITY
        applyBlur()
        sync()
    }

    /**
     * The glass's speed between its last two frames, in dp per ms of motion time (negative: folding up): the
     * motion runs in time the animator scale stretches, so a real dp per ms is that scale's dp per motion ms.
     */
    private fun speed(): Float {
        val dt = (nowNanos - lastNanos) / 1e6f
        if (lastNanos == 0L || dt <= 0f || dt > 50f) return 0f
        return (nowDp - lastDp) / dt * scale()
    }

    private fun fullDp(): Float = glass.fullHeightPx / density

    /** Before each frame: the root view framed to the glass as it shows, the blur's corners with it. */
    private fun frame() {
        val root = dialog.window?.decorView as? ViewGroup ?: return
        val content = root.getChildAt(0) ?: return
        val w = content.width
        val h = content.height
        if (w == 0 || h == 0) return
        val shownPx = min((glass.heightDp * density).roundToInt(), glass.fullHeightPx.takeIf { it > 0 } ?: Int.MAX_VALUE)
        val follow = glass.blur && glass.fullHeightPx > 0
        // The frame is cut to the glass as far as it shows: at its bottom on the status bar; standing on a taskbar, where the
        // glass shows from the bottom up, at its top, with the contents moved up by as much so that what is drawn and where
        // touches land stay put. (Sliding a cut frame down instead, Samsung blurred where the frame would be unslid, at the
        // top of the window: a frosted patch stood out above the glass. Moving the window lagged a frame behind.)
        // At rest the whole card shows: nothing is cut. (Its contents growing while open, a reading coming in, gave a frame
        // or more where the glass still had the old height: standing on a taskbar, the cut pushed the card down by the
        // difference, into the bar, and it bounced back.)
        val shown = if (follow && phase != Phase.OPEN) shownPx.coerceIn(1, h) else h
        val top = if (above) h - shown else 0
        val bottom = if (above) h else shown
        val corner = min(MenuMotion.RADIUS_DP * density, shown / 2f)
        if (Log.isLoggable(MOTION_TAG, Log.DEBUG)) Log.d(MOTION_TAG, "blur top=$top bottom=$bottom")
        if (follow || framed) {
            // A layout pass gives the root view the whole window again, so the frame is looked at every time; but set
            // only when it differs: each change asks for another frame, and at rest that ran 60 frames a second.
            framed = follow
            val work = GlassFrame.work(root.right, root.bottom, w, bottom, corner, lastCorner, root.top, top)
            outline.corner = corner
            if (work.bounds) root.setLeftTopRightBottom(0, top, w, bottom)
            if (work.outline) { lastCorner = corner; root.invalidateOutline() }
        } else outline.corner = corner
        // Also after a layout pass gave the root the whole window back: the contents' shift goes with the frame's top.
        if (root.translationY != 0f) root.translationY = 0f
        if (content.translationY != -top.toFloat()) content.translationY = -top.toFloat()
    }

    private fun applyBlur() {
        val window = dialog.window ?: return
        val r = if (!glass.blur) 0
            else (blurPx * glass.presence * glass.alpha).roundToInt().coerceAtLeast(if (phase == Phase.CLOSING || phase == Phase.DISSOLVING) 0 else 1)
        // Where the platform's blur is off (a Samsung tablet), Samsung's own window blur, as its notification panel has.
        val samsung = SemBlur.wanted && !wm.isCrossWindowBlurEnabled
        val fade = if (samsung) samsungFade() else 1f
        val given = if (!samsung) r
            // Never 0: taking Samsung's blur off a window that is still up left it black for its last frames.
            else (r.toFloat() / blurPx * SemBlur.RADIUS_PX * fade).roundToInt().coerceAtLeast(1)
        if (given != lastBlur) {
            lastBlur = given
            if (samsung) SemBlur.apply(window.decorView, given, MenuMotion.RADIUS_DP * density)
            else window.setBackgroundBlurRadius(r)
        }
    }

    /**
     * Samsung's blur on a popup standing on a taskbar, 0 to 1, by how far the glass is up: whole from [SAMSUNG_FADE_FROM] of
     * its height, none below [SAMSUNG_GONE]. While the glass is nearly flat on the bar, Samsung's blur drew a narrow smear at
     * one end of it, as the popup started to open and as it folded away; the glass's veil goes that part of the way alone.
     * Whole at rest, and always on the status bar.
     */
    private fun samsungFade(): Float {
        if (!above || phase == Phase.OPEN || phase == Phase.GONE) return 1f
        val full = fullDp().takeIf { it > 0f } ?: return 0f // not measured yet: it is a lip on the bar
        return ((glass.heightDp / full - SAMSUNG_GONE) / (SAMSUNG_FADE_FROM - SAMSUNG_GONE)).coerceIn(0f, 1f)
    }
    /** A popup on a taskbar has no shadow while it moves; this is how far the shadow has come in since it rested. */
    private fun shadowIn(): Float = if (restedAt < 0) 0f else ((SystemClock.uptimeMillis() - restedAt) / SHADOW_IN_MS.toFloat()).coerceIn(0f, 1f)
    /** When the popup came to rest open ([SystemClock.uptimeMillis]); -1 while it moves. */
    private var restedAt = -1L
    /** The shadow coming in after the popup rests on a taskbar: a frame at a time until it is whole. */
    private val frostIn = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!shown || phase != Phase.OPEN) return
            glass.shadow = shadowIn()
            sync()
            if (shadowIn() < 1f) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Leaving: touches and keys go to whatever is under it from now on. */
    private fun passThrough() = setFlags(
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
    )

    private fun takeTouches() = setFlags(
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
    )

    private fun setFlags(add: Int, clear: Int) {
        val window = dialog.window ?: return
        runCatching { window.addFlags(add); window.clearFlags(clear) }
    }

    private fun remove() {
        if (!shown) return
        shown = false
        phase = Phase.GONE
        main.removeCallbacks(removal)
        Choreographer.getInstance().removeFrameCallback(tick)
        runCatching { wm.removeCrossWindowBlurEnabledListener(blurListener) }
        dialog.window?.decorView?.viewTreeObserver?.let { if (it.isAlive) it.removeOnPreDrawListener(frameGlass) }
        runCatching { dialog.dismiss() }
        shade.destroy()
        models.viewModelStore.clear()
        onGone?.invoke(); onGone = null
    }

    /** Frames a second of the display the popup is on. */
    private fun refresh(): Long = (dialog.window?.decorView?.display?.refreshRate ?: 60f).toLong().coerceAtLeast(30)

    /** The system's animator duration scale: 1 normally, 0 with "Remove animations" (then nothing moves). */
    private fun scale(): Float = Motion.scale(service)

    companion object {
        /** Debug builds (`./bento debug solid on`): popups as if the platform had no blur, to see the solid card. */
        @Volatile var solid = false

        /** The glass's blur (visual.md: 24 dp, a little more than Booklight's 22 for small text under it). */
        const val BLUR_DP = 24f
        /** Each frame of an opening, in debug logs: `adb shell setprop log.tag.BentoBarMotion DEBUG`. */
        private const val MOTION_TAG = "BentoBarMotion"
        /** The opening's clock starts this long after its first frame was asked for, if Android hasn't said it was drawn. */
        private const val FIRST_FRAME_WAIT_NS = 150_000_000L
        /** A rising popup's Samsung blur fades from this share of the glass's height down to [SAMSUNG_GONE]. */
        private const val SAMSUNG_FADE_FROM = 0.6f
        private const val SAMSUNG_GONE = 0.3f
        /** How long the shadow takes to come in on a popup at rest on a taskbar. */
        private const val SHADOW_IN_MS = 160L
        /** How long after its fold should have ended a closing window is taken away regardless. */
        private const val REMOVAL_GRACE_MS = 250L
    }
}

/**
 * A popup window's background: it paints nothing (the Compose content paints the glass), but its
 * outline is the glass's rounded rectangle, from which the platform takes the blur's corners before
 * each frame. The bounds are the root view's, framed to the glass as far as it shows.
 */
/**
 * Samsung's window blur (One UI): `View.semSetBlurInfo` with a `SemBlurInfo` in its window mode, which blurs what is
 * behind the view live, as the notification panel's glass is. Not in Android's SDK, so it is found by name; where it
 * isn't there, nothing changes. Used where Android's own window blur is off for apps, as on a Samsung tablet that
 * blurs only its own windows that way: there `semSetBlurInfo` puts a blur region on the popup's surface, as the
 * notification panel's glass has. Debug builds can turn it off (`./bento debug semblur off`) to see the solid card.
 */
internal object SemBlur {
    @Volatile var enabled = true
    val wanted get() = enabled && available
    private val info = runCatching { Class.forName("android.view.SemBlurInfo") }.getOrNull()
    private val builder = runCatching { Class.forName("android.view.SemBlurInfo\$Builder") }.getOrNull()
    private val windowMode = runCatching { info!!.getField("BLUR_MODE_WINDOW").getInt(null) }.getOrNull()
    private val set = runCatching { android.view.View::class.java.getMethod("semSetBlurInfo", info) }.getOrNull()
    val available get() = info != null && builder != null && windowMode != null && set != null

    /**
     * Samsung's Quick Settings glass, as its compositor listed it on a tablet: a blur of 300 px, no colour curve, and a
     * background colour adjustment of 0.10 that holds what shows through between 0.18 and 0.85 (a light page behind is
     * dimmed, a dark one lifted), so the glass looks alike over anything. BentoBar's veil goes over it ([veilAt]).
     */
    const val RADIUS_PX = 300
    private val BGCA = floatArrayOf(0.10f, 0.18f, 0.85f)

    /**
     * The tone a popup gets from "Popup glass" at [slider]: as set, or, following the status bar ([barDark] not null),
     * as far from the middle as set, on the bar's side: a dark bar (light text) gets dark glass with light text, a
     * light bar light glass with dark text. At the middle that is Quick Settings' glass with the bar's text colour.
     */
    fun toneFor(slider: Int, barDark: Boolean?): Int {
        if (barDark == null) return slider
        val d = kotlin.math.abs(slider - 50)
        return if (barDark) 50 + d else (49 - d).coerceAtLeast(0)
    }

    /** Whether "Popup glass" at [tone] (0 to 100) has light text, as Quick Settings has: from the middle up. */
    fun dark(tone: Int) = tone >= 50

    /**
     * The veil BentoBar draws over the glass at [tone], as the alpha of the light (below 50) or dark (above) surface
     * colour: none at 50, which is Quick Settings' glass as it is; milkier toward 0, deeper toward 100.
     */
    fun veilAt(tone: Int): Float = if (tone < 50) 0.55f * (50 - tone) / 50f else 0.40f * (tone - 50) / 50f

    private fun call(target: Any, name: String, vararg values: Number) {
        val m = target.javaClass.methods.first { it.name == name && it.parameterTypes.size == values.size }
        val args = m.parameterTypes.mapIndexed { i, t ->
            when (t) {
                Float::class.javaPrimitiveType -> values[i].toFloat()
                Int::class.javaPrimitiveType -> values[i].toInt()
                else -> values[i]
            }
        }.toTypedArray()
        m.invoke(target, *args)
    }

    /** Blurs what is behind [view] by [radius] px with [corner] px corners, as Quick Settings does; 0 takes the blur away. */
    fun apply(view: android.view.View, radius: Int, corner: Float): Boolean = runCatching {
        if (!available) return false
        if (radius <= 0) { set!!.invoke(view, null); return true }
        val b = builder!!.getConstructor(Int::class.javaPrimitiveType).newInstance(windowMode)
        call(b, "setRadius", radius)
        call(b, "setBackgroundCornerRadius", corner)
        runCatching { call(b, "setBGColorAdjustment", BGCA[0], BGCA[1], BGCA[2]) }
            .onFailure { Log.w("BentoBar", "Samsung blur: no background adjustment (${it.javaClass.simpleName})") }
        set!!.invoke(view, builder.getMethod("build").invoke(b))
        true
    }.onFailure { Log.w("BentoBar", "Samsung blur failed: ${it.javaClass.simpleName} ${it.cause?.javaClass?.simpleName}") }.getOrDefault(false)
}

private class GlassOutline : Drawable() {
    var corner = 0f
    override fun draw(canvas: Canvas) {}
    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, min(corner, min(bounds.width(), bounds.height()) / 2f))
        outline.alpha = 1f
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
