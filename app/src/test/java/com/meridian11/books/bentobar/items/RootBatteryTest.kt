package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootBatteryTest {
    @Test fun samsungsFilesAsATabletGaveThem() {
        assertEquals(RootBattery.Info(cycles = 22, healthPct = 100), RootBattery.parse("battery_cycle=22\nfg_asoc=100\n"))
    }

    @Test fun whatIsMissingOrOutOfRangeIsLeftOut() {
        assertEquals(RootBattery.Info(cycles = 5, healthPct = null), RootBattery.parse("battery_cycle=5\nfg_asoc=250"))
        assertEquals(RootBattery.Info(cycles = null, healthPct = 87), RootBattery.parse("fg_asoc=87"))
        // su refused: its words, no numbers.
        assertNull(RootBattery.parse("Permission denied"))
        assertNull(RootBattery.parse(""))
    }
}
