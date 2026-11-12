package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.util.Sym
import org.junit.Assert.assertEquals
import org.junit.Test

class ChargeSpeedTest {
    @Test fun theChargersStepIsReadFromSamsungsExtras() {
        assertEquals(ChargeSpeed.NORMAL, ChargeSpeed.of(0, false))
        assertEquals(ChargeSpeed.FAST, ChargeSpeed.of(0, true))
        assertEquals(ChargeSpeed.FAST, ChargeSpeed.of(1, true))
        assertEquals(ChargeSpeed.FAST, ChargeSpeed.of(2, true))
        // A 25 W charger on a tablet that takes 45 W: 3, not 2.0.
        assertEquals(ChargeSpeed.SUPER, ChargeSpeed.of(3, true))
        assertEquals(ChargeSpeed.SUPER_2, ChargeSpeed.of(4, true))
        // A faster step than this knows is still the fastest it draws.
        assertEquals(ChargeSpeed.SUPER_2, ChargeSpeed.of(5, true))
    }

    @Test fun superFastIsBlueAndTwoPointOhHasTwoBolts() {
        assertEquals(BatteryBand.GREEN, ChargeSpeed.NORMAL.color)
        assertEquals(BatteryBand.GREEN, ChargeSpeed.FAST.color)
        assertEquals(ChargeSpeed.BLUE, ChargeSpeed.SUPER.color)
        assertEquals(ChargeSpeed.BLUE, ChargeSpeed.SUPER_2.color)
        assertEquals(Sym.BOLT, ChargeSpeed.SUPER.glyph)
        assertEquals(ChargeSpeed.DOUBLE_BOLT, ChargeSpeed.SUPER_2.glyph)
    }
}
