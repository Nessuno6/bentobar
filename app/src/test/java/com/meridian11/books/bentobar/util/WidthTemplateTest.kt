package com.meridian11.books.bentobar.util

import org.junit.Assert.assertEquals
import org.junit.Test

class WidthTemplateTest {
    private fun t(s: String) = Fmt.widthTemplate(s)

    @Test fun networkHoldsThreeDigitsAndTheWidestUnit() {
        assertEquals("↓888M ↑888M", t("↓0K ↑0K"))
        assertEquals("↓888M ↑888M", t("↓8.4K ↑3.5K"))
    }

    @Test fun samePlaceForEveryReading() {
        // Any whole number up to 999 with a unit fits the slot of the smallest reading.
        val slot = t("↓0K ↑0K")
        for (r in listOf("↓8.4K ↑3.5K", "↓999M ↑123K", "↓12K ↑0K", "↓1.2G ↑9.9M")) assertEquals(r, slot, t(r))
    }

    @Test fun percentages() {
        assertEquals("888%", t("5%"))
        assertEquals("888%", t("\u20075%"))
        assertEquals("888%", t("100%"))
        assertEquals("\u20077%", Fmt.percentTwoDigit(0.07))
        assertEquals("17%", Fmt.percentTwoDigit(0.17))
    }

    @Test fun clocksKeepTheirShape() {
        assertEquals("88:88", t("9:42"))
        assertEquals("88:88:88", t("1:23:45"))
        assertEquals("88:88 PM", t("9:42 PM"))
    }

    @Test fun lettersStay() {
        assertEquals("Meeting in 888m", t("Meeting in 5m"))
    }
}

class WidthTemplateUnitsTest {
    @Test fun unitsAfterNumbersOnly() {
        assertEquals("888 MB", Fmt.widthTemplate("15.9 GB"))
        assertEquals("Keep 888m", Fmt.widthTemplate("Keep 25m"))
    }
}

class DigitSlotTest {
    @Test fun everyNumberHoldsTwoDigits() {
        assertEquals("88%", Fmt.digitSlot("7%"))
        assertEquals(Fmt.digitSlot("7%"), Fmt.digitSlot("20%"))
        assertEquals("88 new", Fmt.digitSlot("3 new"))
        assertEquals("888", Fmt.digitSlot("100"))
        assertEquals("88.88 K/s", Fmt.digitSlot("0.44 K/s"))
    }

    @Test fun wordsAloneHaveNoSlot() {
        org.junit.Assert.assertNull(Fmt.digitSlot("Muted"))
    }
}
