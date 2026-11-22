package com.meridian11.books.bentobar.bar

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors

/** Android's rectangle as a plain [Box], and back. */
internal fun Rect.box() = Box(left, top, right, bottom)
internal fun Box.rect() = Rect(left, top, right, bottom)

/**
 * Reads the accessibility tree into plain facts for [BarRules]. The status bar is the only window whose content is
 * read (SystemUI's status bar window, as [BarNode]s, which become a [BarSnapshot]); of any other window, only its kind,
 * its id and where it is ([WindowFact]), and of one in the bar's place whose root it is.
 */
object StatusBarScan {
    /**
     * A see-through panel over the whole screen and past it on every side (found on a Samsung tablet: the edge panel,
     * 28 px past each edge). With it open the window list holds it and the navigation bar only, no status bar,
     * though the bar still shows above it. The notification shade, which does cover the bar, is exactly the
     * screen's size. Pure, so it is unit-tested.
     */
    fun overhangs(left: Int, top: Int, right: Int, bottom: Int, screenW: Int, screenH: Int): Boolean =
        left < 0 && top < 0 && right > screenW && bottom > screenH

    /** The display's cutouts (a phone's camera hole) as they stand in its current rotation, in screen pixels. */
    fun cutouts(display: android.view.Display?): List<Box> =
        runCatching { display?.cutout?.boundingRects?.map { it.box() } }.getOrNull().orEmpty().filter { it.width() > 0 && it.height() > 0 }

    /** The radius of the display's top-left rounded corner, in px; null where the display doesn't say. */
    fun corner(display: android.view.Display?): Int? =
        runCatching { display?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius }.getOrNull()?.takeIf { it > 0 }

    /**
     * What a scan brings back: the plain [snapshot], and the live spacer node (a cheap "did anything move?" check for
     * [BarController]). No snapshot: the window is not SystemUI's ([notTheBar]), and nothing under its root was fetched.
     */
    class Scanned(val snapshot: BarSnapshot?, val spacerNode: AccessibilityNodeInfo?) {
        val notTheBar get() = snapshot == null
    }

    /** What [BarRules] may know of [w]: its kind, its id and where it is. */
    fun fact(w: AccessibilityWindowInfo): WindowFact {
        val kind = when (w.type) {
            AccessibilityWindowInfo.TYPE_SYSTEM -> WindowFact.Kind.SYSTEM
            AccessibilityWindowInfo.TYPE_APPLICATION -> WindowFact.Kind.APPLICATION
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowFact.Kind.OVERLAY
            else -> WindowFact.Kind.OTHER
        }
        return WindowFact(w.id, kind, Rect().also { w.getBoundsInScreen(it) }.box())
    }

    /** [cutouts]: the display's cutouts, in screen pixels (`BarRules.parse`). */
    /** [edge]: what each end of a phone's bar keeps clear, in px (`BarRules.parse`); null for one bar height. */
    fun scan(w: AccessibilityWindowInfo, cutouts: List<Box> = emptyList(), edge: Int? = null): Scanned {
        val bar = Rect().also { w.getBoundsInScreen(it) }.box()
        fun noTree() = Scanned(BarRules.parse(w.id, bar, emptyList(), cutouts, edge), null)
        // Whose window it is, from its root alone, fetched with nothing under it: another app's window in the bar's place
        // (an overlay the list calls a system window) is not walked, and its tree is never fetched.
        val owner = root(w, 0) ?: return noTree()
        if (!BarRules.isStatusBarPackage(owner.packageName?.toString())) return Scanned(null, null)
        // Then the tree in one more round trip: the root again (a window's root is never the node cache's), with the
        // (small, ~35 node) tree prefetched, instead of an IPC per getChild().
        val root = root(w, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or
            AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE) ?: return noTree()
        var spacerNode: AccessibilityNodeInfo? = null
        val nodes = ArrayList<BarNode>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        while (stack.isNotEmpty() && nodes.size < 400) {
            val (n, depth) = stack.removeLast()
            val id = n.viewIdResourceName.orEmpty()
            if (BarRules.isSpacer(id)) spacerNode = n
            nodes += BarNode(id, Rect().also { n.getBoundsInScreen(it) }.box(), depth, n.text?.toString().orEmpty(),
                described = !n.contentDescription.isNullOrEmpty(), clickable = n.isClickable, children = n.childCount)
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.add(it to depth + 1) }
        }
        return Scanned(BarRules.parse(w.id, bar, nodes, cutouts, edge), spacerNode)
    }

    /**
     * A desktop taskbar along the bottom edge of an external display (Samsung DeX): a system window across the screen's
     * width, against its bottom, shorter than [maxHeight]. The monitor has no status bar, so BentoBar sits in this one.
     */
    fun findTaskbar(windows: List<AccessibilityWindowInfo>, screenWidth: Int, screenHeight: Int, maxHeight: Int): AccessibilityWindowInfo? =
        windows.firstOrNull { w ->
            val r = Rect().also { w.getBoundsInScreen(it) }
            w.type == AccessibilityWindowInfo.TYPE_SYSTEM && r.bottom == screenHeight && r.height() in 1..maxHeight && r.width() >= screenWidth * 9 / 10
        }

    /**
     * The taskbar's free part: from the end of whatever is left of its system icons (the navigation buttons, the apps)
     * to the start of the system icons ([TRAY]: screen capture, volume, notifications, quick settings, the clock), so
     * BentoBar's strip ends right before them. Without the system icons, the widest gap. The apps' row
     * ([APPS]) is the node a light check refreshes: it grows as apps open.
     */
    fun scanTaskbar(w: AccessibilityWindowInfo): Scanned {
        val bar = Rect().also { w.getBoundsInScreen(it) }
        val root = root(w, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or
            AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE) ?: return Scanned(BarSnapshot(w.id, bar.box(), bar.box(), null, BarRules.NO_TREE), null)
        var tray: Rect? = null
        var clock: Rect? = null
        var apps: AccessibilityNodeInfo? = null
        var appsAt: Rect? = null
        val parts = ArrayList<Rect>()
        val names = StringBuilder()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (stack.isNotEmpty() && visited < 400) {
            val n = stack.removeLast()
            visited++
            val r = Rect().also { n.getBoundsInScreen(it) }
            val id = n.viewIdResourceName.orEmpty().substringAfter(":id/")
            when (id) {
                TRAY -> tray = Rect(r)
                CLOCK -> clock = Rect(r)
                APPS -> { apps = n; appsAt = Rect(r) }
            }
            if (id.isNotEmpty() && r.width() in 1 until bar.width() * 6 / 10 && r.height() > 0) { parts += Rect(r); names.append(id).append(' ') }
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.add(it) }
        }
        val free = tray?.let { t -> freeBefore(bar.left, t.left, parts.map { it.left to it.right }).let { (l, r) -> Rect(l, bar.top, r, bar.bottom) } }
            ?: widestGap(bar, parts)
        return Scanned(BarSnapshot(w.id, bar.box(), free.box(), clock?.box(), names.toString().trim(), spacer = appsAt?.box()), apps)
    }

    /** From the right end of the last span that ends before [end] (or from [start]) up to [end]; pure, unit-tested. */
    fun freeBefore(start: Int, end: Int, spans: List<Pair<Int, Int>>): Pair<Int, Int> =
        (spans.filter { it.second <= end }.maxOfOrNull { it.second } ?: start).coerceIn(start, end) to end

    private const val TRAY = "status_panel_container"
    private const val CLOCK = "date_and_clock"
    private const val APPS = "task_bar"

    /** The widest horizontal stretch of the bar that no item covers. */
    private fun widestGap(bar: Rect, parts: List<Rect>): Rect {
        val spans = parts.map { it.left to it.right }.sortedBy { it.first }
        var best = Rect(bar.left, bar.top, bar.left, bar.bottom)
        var cursor = bar.left
        for ((l, r) in spans) {
            if (l > cursor && l - cursor > best.width()) best = Rect(cursor, bar.top, l, bar.bottom)
            cursor = maxOf(cursor, r)
        }
        if (bar.right - cursor > best.width()) best = Rect(cursor, bar.top, bar.right, bar.bottom)
        return best
    }

    /** The one way to a window's root. [prefetch] says what comes with it: 0 for the root alone. */
    private fun root(w: AccessibilityWindowInfo, prefetch: Int): AccessibilityNodeInfo? = w.getRoot(prefetch)

    /**
     * The screenshots' answers, on one thread for the whole process, never shut down: a controller that stops must not
     * leave an answer with nowhere to run, because running it is what closes the screenshot's buffer ([barColors]). A
     * result that arrives after stop() is then dropped on the main thread by [ColorReader]'s started check.
     */
    private val answers = Executors.newSingleThreadExecutor()

    /**
     * A reading: a screenshot of the status bar window only, its pixels read for [ColorReader]. [done] gets the colours
     * (null: the picture gave none), or the code of Android's refusal as its second argument.
     */
    fun shoot(service: AccessibilityService, s: BarSnapshot, done: (BarColors?, Int?) -> Unit) {
        service.takeScreenshotOfWindow(s.windowId, answers, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) { done(runCatching { barColors(r, s) }.getOrNull(), null) }
            override fun onFailure(code: Int) { done(null, code) }
        })
    }

    /** The pixels around the clock (the whole bar when no clock was found), as [BarPixels] reads them. */
    private fun barColors(r: AccessibilityService.ScreenshotResult, s: BarSnapshot): BarColors? {
        val hb = r.hardwareBuffer
        val bmp = try {
            Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.let { wrapped -> wrapped.copy(Bitmap.Config.ARGB_8888, false).also { wrapped.recycle() } }
        } finally { hb.close() }
        bmp ?: return null
        try {
            val area = (s.clock ?: s.bar).rect().apply { offset(-s.bar.left, -s.bar.top) }
            // A clock box outside the picture (the bar changed size since the scan): read all of it.
            if (!area.intersect(0, 0, bmp.width, bmp.height)) area.set(0, 0, bmp.width, bmp.height)
            val px = IntArray(area.width() * area.height())
            bmp.getPixels(px, 0, area.width(), area.left, area.top, area.width(), area.height())
            return BarPixels.colors(px, area.width())
        } finally { bmp.recycle() }
    }
}
