package com.meridian11.books.bentobar.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the status bar's tree says, from the nodes the walk found (the walk itself is the edge: `StatusBarScan.scan`).
 * The tree is the one docs/research/device-findings.md recorded on a 14-inch Googlebook.
 */
class StatusBarScanTest {
    @Test fun onATaskbarTheStripEndsAtTheSystemIcons() {
        // A 2560 px taskbar: back/home/recents to 179, the apps 1039 to 1521, the system icons from 2226 on.
        assertEquals(1521 to 2226, StatusBarScan.freeBefore(0, 2226, listOf(29 to 179, 1039 to 1521, 1100 to 1150, 2238 to 2541)))
        // Nothing before the system icons: from the taskbar's start.
        assertEquals(0 to 2226, StatusBarScan.freeBefore(0, 2226, listOf(2238 to 2541)))
    }

    @Test fun theEdgePanelOverhangsTheScreenAndTheShadeDoesNot() {
        // As listed on a 2960×1848 tablet: the edge panel, then the notification shade.
        assertTrue(StatusBarScan.overhangs(-28, -28, 2988, 1876, 2960, 1848))
        assertFalse(StatusBarScan.overhangs(0, 0, 2960, 1848, 2960, 1848))
        // The navigation bar, and a window past one edge only.
        assertFalse(StatusBarScan.overhangs(0, 1822, 2960, 1848, 2960, 1848))
        assertFalse(StatusBarScan.overhangs(-28, 0, 2960, 1848, 2960, 1848))
    }

    private val bar = Box(0, 0, 1920, 41)
    private fun node(id: String, left: Int, right: Int, depth: Int = 1, text: String = "", described: Boolean = false, clickable: Boolean = false, children: Int = 0) =
        BarNode(id, Box(left, 0, right, 41), depth, text, described, clickable, children)

    private val recordedTree = listOf(
        BarNode("", bar, 0, children = 6),
        node("dateTimeChip", 27, 168, clickable = true, described = true, children = 2),
        node("clock", 27, 95, depth = 2, text = "9:19", described = true),
        node("date", 100, 168, depth = 2, text = "Sun, Sep 27"),
        node("DesktopStatusBarSpacer", 168, 1681),
        node("ImeIndicator", 1681, 1722, text = "US", described = true, clickable = true),
        node("ContextualCursor", 1722, 1763, described = true, clickable = true),
        node("notificationIcons", 1763, 1817, described = true, clickable = true),
        node("system_icons", 1817, 1893, described = true, clickable = true, children = 2),
        node("statusIcons", 1817, 1860, depth = 2, described = true),
        node("battery", 1860, 1893, depth = 2, described = true),
    )

    @Test fun theRecordedBarFindsItsSpacerAndItsClockAndNamesItsItems() {
        val s = BarRules.parse(7, bar, recordedTree)
        assertEquals(Box(168, 0, 1681, 41), s.free)
        assertEquals(Box(168, 0, 1681, 41), s.spacer)
        assertEquals(Box(27, 0, 95, 41), s.clock)
        assertEquals(7, s.windowId)
        // The spacer itself is not labelled, so it is no part of the fingerprint.
        assertTrue(s.summary, "dateTimeChip" in s.summary && "battery" in s.summary && "DesktopStatusBarSpacer" !in s.summary)
    }

    @Test fun aResourceNameCountsByItsEnd() {
        val named = recordedTree.map { if (it.id == "DesktopStatusBarSpacer") it.copy(id = "com.android.systemui:id/DesktopStatusBarSpacer") else it }
        assertEquals(Box(168, 0, 1681, 41), BarRules.parse(7, bar, named).free)
    }

    @Test fun aBarWithoutTheSpacerUsesItsWidestGap() {
        val noSpacer = recordedTree.filterNot { it.id == "DesktopStatusBarSpacer" }
        val s = BarRules.parse(7, bar, noSpacer)
        assertEquals(Box(168, 0, 1681, 41), s.free)
        assertNull(s.spacer)
    }

    @Test fun aSpacerSqueezedToNothingGivesWayToTheWidestGapAndIsStillRemembered() {
        // lightCheck compares against the spacer's own bounds: compared with the free area it would run a full scan every time.
        val squeezed = recordedTree.map { if (it.id == "DesktopStatusBarSpacer") it.copy(box = Box(168, 0, 168, 41)) else it }
        val s = BarRules.parse(7, bar, squeezed)
        assertEquals(Box(168, 0, 168, 41), s.spacer)
        assertEquals(Box(168, 0, 1681, 41), s.free)
    }

    @Test fun aCameraHoleTakesRoomAsAnItemDoes() {
        // A phone's bar: a chip at the left, the camera hole in the middle, the system icons at the right. Without the
        // hole the widest gap ran across the camera and the strip stood under it, its icon hidden by the hole.
        val phone = Box(0, 0, 1440, 128)
        fun at(id: String, l: Int, r: Int) = BarNode(id, Box(l, 0, r, 128), 1, "", described = true)
        val tree = listOf(BarNode("", phone, 0, children = 3), at("chip", 50, 470), at("system_icons", 900, 1400))
        val hole = Box(680, 20, 760, 100)
        assertEquals(Box(470, 0, 900, 128), BarRules.parse(7, phone, tree).free)
        assertEquals(Box(470, 0, 680, 128), BarRules.parse(7, phone, tree, listOf(hole)).free)
        // A hole below the bar (a tablet's camera in its bezel, or the bar elsewhere) changes nothing.
        assertEquals(Box(470, 0, 900, 128), BarRules.parse(7, phone, tree, listOf(Box(680, 200, 760, 280))).free)
        // The spacer stops short of the hole too, on the wider side.
        val spaced = tree + BarNode("DesktopStatusBarSpacer", Box(470, 0, 900, 128), 1)
        assertEquals(Box(470, 0, 680, 128), BarRules.parse(7, phone, spaced, listOf(hole)).free)
    }

    @Test fun onAPhoneTheBarsEndsAreNoRoom() {
        // As seen on a phone with a call chip: its node starts at 137, after its own icon, and the stretch before it is the
        // rounded corner and that icon. With the camera splitting the middle, the room is the stretch right of the camera.
        val phone = Box(0, 0, 1440, 128)
        fun at(id: String, l: Int, r: Int) = BarNode(id, Box(l, 0, r, 128), 1, "", described = true)
        val tree = listOf(BarNode("", phone, 0, children = 4), at("chip", 137, 470), at("notificationIcons", 480, 610), at("system_icons", 880, 1400))
        assertEquals(Box(755, 0, 880, 128), BarRules.parse(7, phone, tree, listOf(Box(685, 0, 755, 128))).free)
        // Nothing left of the camera: the room is all of it, but the corner (one bar's height).
        val bare = listOf(BarNode("", phone, 0, children = 2), at("system_icons", 880, 1400))
        assertEquals(Box(128, 0, 685, 128), BarRules.parse(7, phone, bare, listOf(Box(685, 0, 755, 128))).free)
        // With the screen's corner given (56 px, less the strip's 22 px gap), only that much is kept clear.
        assertEquals(Box(34, 0, 685, 128), BarRules.parse(7, phone, bare, listOf(Box(685, 0, 755, 128)), edge = 34).free)
    }

    @Test fun aPhoneShowsThreeItemsAndOneBesideTheNowBar() {
        val phone = Box(0, 0, 1440, 128)
        fun at(id: String, l: Int, r: Int) = BarNode(id, Box(l, 0, r, 128), 1, "", described = true)
        val hole = listOf(Box(685, 0, 755, 128))
        val icons = listOf(BarNode("", phone, 0, children = 3), at("notificationIcons", 480, 610), at("system_icons", 880, 1400))
        assertEquals(3, BarRules.parse(7, phone, icons, hole).cap)
        // The Now Bar's chip: one wide item left of the camera.
        assertEquals(1, BarRules.parse(7, phone, icons + at("chip", 137, 470), hole).cap)
        // No camera in the bar: no limit (a Googlebook, a tablet).
        assertNull(BarRules.parse(7, phone, icons + at("chip", 137, 470)).cap)
    }

    @Test fun onAPhoneTheStripTakesTheLeftmostRoomThatHoldsIt() {
        val phone = Box(0, 0, 1440, 128)
        fun at(id: String, l: Int, r: Int) = BarNode(id, Box(l, 0, r, 128), 1, "", described = true)
        val hole = listOf(Box(685, 0, 755, 128))
        // Icons at the left, then 230 px free, the camera, and a wider stretch right of it: the left one.
        val tree = listOf(BarNode("", phone, 0, children = 3), at("notificationIcons", 128, 455), at("system_icons", 1200, 1400))
        assertEquals(Box(455, 0, 685, 128), BarRules.parse(7, phone, tree, hole).free)
        // Too little left of the camera (75 px): the widest stretch, right of it.
        val full = listOf(BarNode("", phone, 0, children = 3), at("notificationIcons", 128, 610), at("system_icons", 900, 1400))
        assertEquals(Box(755, 0, 900, 128), BarRules.parse(7, phone, full, hole).free)
    }

    @Test fun aNodeAsWideAsTheBarIsNoItem() {
        // A wrapper around the whole bar must not fill it: only leaf-ish pieces take room.
        val wrapper = node("wrapper", 0, 1920, described = true)
        val s = BarRules.parse(7, bar, listOf(BarNode("", bar, 0, children = 1), wrapper))
        assertEquals(bar, s.free)
    }

    @Test fun aPieceOfNoSizeTakesNoRoom() {
        val flat = BarNode("flat", Box(500, 0, 900, 0), 1, described = true)
        val narrow = node("narrow", 500, 500, described = true)
        assertEquals(bar, BarRules.parse(7, bar, listOf(BarNode("", bar, 0, children = 2), flat, narrow)).free)
    }

    @Test fun piecesThatOverlapAreOneStretch() {
        val nodes = listOf(BarNode("", bar, 0, children = 3), node("a", 0, 100, clickable = true), node("b", 50, 120, clickable = true), node("c", 1000, 1920, clickable = true))
        assertEquals(Box(120, 0, 1000, 41), BarRules.parse(7, bar, nodes).free)
    }

    @Test fun theClockIsTheFirstNodeThatSaysATime() {
        val nodes = listOf(BarNode("", bar, 0, children = 3), node("a", 0, 90, text = "Sep 27"), node("b", 100, 190, text = "21.05"), node("c", 200, 290, text = "9:19"))
        assertEquals(Box(100, 0, 190, 41), BarRules.parse(7, bar, nodes).clock)
    }

    @Test fun onlyNodesNearTheTopOfTheTreeNameTheBar() {
        val deep = listOf(BarNode("", bar, 0, children = 1), node("deepItem", 10, 90, depth = 7, clickable = true), node("shallowItem", 100, 190, depth = 6, clickable = true))
        assertEquals("shallowItem", BarRules.parse(7, bar, deep).summary)
    }

    @Test fun aWindowWithNoTreeSaysSo() {
        val s = BarRules.parse(7, bar, emptyList())
        assertEquals(BarRules.NO_TREE, s.summary)
        assertEquals(bar, s.free)
        assertNull(s.clock)
    }

    @Test fun aRootWithNoChildrenIsNoTree() {
        // SystemUI mid-change: a root and nothing under it. Read as a bar, all of it would be free, and the strip would cover its icons.
        val root = listOf(BarNode("", bar, 0, children = 0))
        assertEquals(BarRules.NO_TREE, BarRules.parse(7, bar, root).summary)
        // And so the last reading of this bar stands, as for a window with no tree at all.
        val last = BarSnapshot(7, bar, Box(168, 0, 1681, 41), null, "items")
        assertEquals(last, BarRules.settled(BarRules.parse(7, bar, root), last))
    }

    @Test fun aRootWithChildrenIsATreeEvenWithNothingLabelledInIt() {
        // Asking for labelled nodes would leave a bar with no spacer without the widest-gap fallback, and the strip would never show there.
        val unlabelled = listOf(BarNode("", bar, 0, children = 1), node("wrapper", 0, 1920))
        val s = BarRules.parse(7, bar, unlabelled)
        assertEquals("", s.summary)
        assertEquals(bar, s.free)
    }

    @Test fun theBarIsTheSystemWindowAlongTheTop() {
        val system = WindowFact(1, WindowFact.Kind.SYSTEM, bar)
        assertEquals(system, BarRules.findBar(listOf(system), 1920, 45))
        fun finds(f: WindowFact) = BarRules.findBar(listOf(f), 1920, 45)
        assertNull("an app's window at the top is no status bar", finds(WindowFact(1, WindowFact.Kind.APPLICATION, bar)))
        assertNull("not along the top", finds(WindowFact(1, WindowFact.Kind.SYSTEM, Box(0, 1, 1920, 42))))
        assertNull("too tall: it is a panel", finds(WindowFact(1, WindowFact.Kind.SYSTEM, Box(0, 0, 1920, 46))))
        assertNull("no height", finds(WindowFact(1, WindowFact.Kind.SYSTEM, Box(0, 0, 1920, 0))))
        assertNull("too narrow", finds(WindowFact(1, WindowFact.Kind.SYSTEM, Box(0, 0, 959, 41))))
        assertEquals("half the screen is enough", 1, finds(WindowFact(1, WindowFact.Kind.SYSTEM, Box(0, 0, 960, 41)))?.id)
    }

    @Test fun theFirstOfTwoBarsIsTheBar() {
        val first = WindowFact(1, WindowFact.Kind.SYSTEM, bar)
        val second = WindowFact(2, WindowFact.Kind.SYSTEM, bar)
        assertEquals(first, BarRules.findBar(listOf(first, second), 1920, 45))
    }

    @Test fun aBoxTouchingAnotherAtAnEdgeDoesNotIntersectIt() {
        val b = Box(100, 0, 200, 41)
        assertTrue(b.intersects(Box(150, 20, 300, 60)))
        assertEquals(false, b.intersects(Box(200, 0, 300, 41)))
        assertEquals(false, b.intersects(Box(100, 41, 200, 90)))
        // As Rect.intersects: a free area squeezed to no width, inside a wide window, still counts as under it.
        assertTrue(Box(150, 0, 150, 41).intersects(Box(0, 0, 1920, 1200)))
    }
}
