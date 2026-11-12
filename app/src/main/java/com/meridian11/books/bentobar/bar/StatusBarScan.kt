package com.meridian11.books.bentobar.bar

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Where the status bar is and which part of it is free, read from SystemUI's status bar window
 * in the accessibility tree. This is the only window BentoBar ever reads; app windows are skipped.
 */
data class BarSnapshot(
    /** The status bar window's id, for window screenshots (colour sampling). */
    val windowId: Int,
    val bar: Rect,
    /** Empty space BentoBar may use: the desktop bar's spacer, or the widest gap between items. */
    val free: Rect,
    /** The clock text, whose colour BentoBar copies. */
    val clock: Rect?,
    /** A short fingerprint of the bar's items, for logs. */
    val summary: String,
    /** The desktop bar's spacer node: refreshing just this is a cheap "did anything move?" check. */
    val spacerNode: AccessibilityNodeInfo? = null,
    /** Where [spacerNode] was at the scan. It is [free] unless the spacer had no width left. */
    val spacer: Rect? = null,
)

object StatusBarScan {
    /** [BarSnapshot.summary] when the window had no node tree to read: its free area is unknown. */
    const val NO_TREE = "no-tree"

    /**
     * A see-through panel over the whole screen and past it on every side (found on a Samsung tablet: the edge panel,
     * 28 px past each edge). With it open the window list holds it and the navigation bar only, no status bar,
     * though the bar still shows above it. The notification shade, which does cover the bar, is exactly the
     * screen's size. Pure, so it is unit-tested.
     */
    fun overhangs(left: Int, top: Int, right: Int, bottom: Int, screenW: Int, screenH: Int): Boolean =
        left < 0 && top < 0 && right > screenW && bottom > screenH
    private val timeRe = Regex("""\d{1,2}[:.]\d{2}""")

    /** The status bar window: a system window along the top edge, shorter than [maxHeight]. */
    fun findWindow(windows: List<AccessibilityWindowInfo>, screenWidth: Int, maxHeight: Int): AccessibilityWindowInfo? =
        windows.firstOrNull { w ->
            val r = Rect().also { w.getBoundsInScreen(it) }
            w.type == AccessibilityWindowInfo.TYPE_SYSTEM && r.top == 0 && r.height() in 1..maxHeight && r.width() >= screenWidth / 2
        }

    fun scan(w: AccessibilityWindowInfo): BarSnapshot {
        val bar = Rect().also { w.getBoundsInScreen(it) }
        // One round trip: prefetch the (small, ~35 node) tree instead of an IPC per getChild().
        val root = w.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or
            AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE) ?: return BarSnapshot(w.id, bar, Rect(bar), null, NO_TREE)
        var spacer: Rect? = null
        var spacerNode: AccessibilityNodeInfo? = null
        var clock: Rect? = null
        val parts = ArrayList<Rect>()
        val names = StringBuilder()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        var visited = 0
        while (stack.isNotEmpty() && visited < 400) {
            val (n, depth) = stack.removeLast()
            visited++
            val r = Rect().also { n.getBoundsInScreen(it) }
            val id = n.viewIdResourceName.orEmpty()
            if (id.endsWith("DesktopStatusBarSpacer")) { spacer = Rect(r); spacerNode = n }
            val text = n.text?.toString().orEmpty()
            if (clock == null && text.isNotEmpty() && timeRe.containsMatchIn(text)) clock = Rect(r)
            // Leaf-ish pieces that take room: anything clickable or labelled that isn't the whole bar.
            val labelled = n.isClickable || !n.contentDescription.isNullOrEmpty() || text.isNotEmpty()
            if (labelled && r.width() in 1 until bar.width() * 6 / 10 && r.height() > 0) {
                parts += Rect(r)
                if (depth <= 6 && id.isNotEmpty()) names.append(id.substringAfterLast('/')).append(' ')
            }
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.add(it to depth + 1) }
        }
        val free = spacer?.takeIf { it.width() > 0 } ?: widestGap(bar, parts)
        return BarSnapshot(w.id, bar, free, clock, names.toString().trim(), spacerNode, spacer)
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
    fun scanTaskbar(w: AccessibilityWindowInfo): BarSnapshot {
        val bar = Rect().also { w.getBoundsInScreen(it) }
        val root = w.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or
            AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE) ?: return BarSnapshot(w.id, bar, Rect(bar), null, NO_TREE)
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
        return BarSnapshot(w.id, bar, free, clock, names.toString().trim(), apps, appsAt)
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
}
