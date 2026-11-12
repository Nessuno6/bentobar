package com.meridian11.books.bentobar.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemBlurToneTest {
    @Test fun followingTheBarPutsTheSlidersDistanceOnTheBarsSide() {
        // At the middle: Quick Settings' glass with the bar's text colour.
        assertTrue(SemBlur.dark(SemBlur.toneFor(50, barDark = true)))
        assertFalse(SemBlur.dark(SemBlur.toneFor(50, barDark = false)))
        // 20 from the middle either way: as deep on a dark bar, as milky on a light one.
        assertEquals(70, SemBlur.toneFor(30, barDark = true))
        assertEquals(29, SemBlur.toneFor(70, barDark = false))
        // Not following: as set.
        assertEquals(30, SemBlur.toneFor(30, barDark = null))
    }

    @Test fun noVeilAtTheMiddle() {
        assertEquals(0f, SemBlur.veilAt(50), 0f)
        assertTrue(SemBlur.veilAt(0) > SemBlur.veilAt(40))
        assertTrue(SemBlur.veilAt(100) > SemBlur.veilAt(60))
    }
}
