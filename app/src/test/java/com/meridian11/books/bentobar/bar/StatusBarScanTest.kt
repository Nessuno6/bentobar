package com.meridian11.books.bentobar.bar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
