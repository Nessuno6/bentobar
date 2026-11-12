package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.Section
import com.meridian11.books.bentobar.items.ItemsShareRules.Shared
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What other apps read of the bar's items: the table's name, columns and values are a promise to their code. */
class ItemsShareRulesTest {
    private val cpu = Shared("i1", "cpu", "CPU", "12%", null, "CPU load 12 percent", active = false, tone = Tone.NORMAL)

    @Test fun theTableIsItems() {
        assertTrue(ItemsShareRules.isItems(listOf("items")))
        assertFalse(ItemsShareRules.isItems(emptyList()))
        assertFalse(ItemsShareRules.isItems(listOf("items", "i1")))
        assertFalse(ItemsShareRules.isItems(listOf("other")))
    }

    @Test fun theColumnsStayAsPublished() {
        assertArrayEquals(arrayOf("id", "type", "title", "text", "label", "description", "active", "tone", "icon_png", "in_bar"), ItemsShareRules.COLUMNS)
        assertEquals(1, ItemsShareRules.VERSION)
    }

    @Test fun anItemIsItsWordsItsToneAndItsIcon() {
        val png = byteArrayOf(1, 2, 3)
        assertArrayEquals(arrayOf<Any?>("i1", "cpu", "CPU", "12%", null, "CPU load 12 percent", 0, "normal", png, 1), ItemsShareRules.row(cpu, png))
        val hot = cpu.copy(text = "", label = "", active = true, tone = Tone.ALERT)
        assertArrayEquals(arrayOf<Any?>("i1", "cpu", "CPU", null, null, "CPU load 12 percent", 1, "alert", null, 1), ItemsShareRules.row(hot, null))
    }

    @Test fun whatTheBarShowsGoesOut() {
        assertTrue(ItemsShareRules.shares(Section.SHOWN, whenActive = false, active = false, gap = false))
        // A hidden item only while its "show when" rule holds.
        assertTrue(ItemsShareRules.shares(Section.HIDDEN, whenActive = true, active = true, gap = false))
        assertFalse(ItemsShareRules.shares(Section.HIDDEN, whenActive = true, active = false, gap = false))
        assertFalse(ItemsShareRules.shares(Section.HIDDEN, whenActive = false, active = true, gap = false))
        // Off is off, and a gap between items is nothing to read.
        assertFalse(ItemsShareRules.shares(Section.OFF, whenActive = true, active = true, gap = false))
        assertFalse(ItemsShareRules.shares(Section.SHOWN, whenActive = false, active = false, gap = true))
    }

    @Test fun anAppThatClaimsItemsGetsThemAndTheBarLetsThemGo() {
        val claim = ClaimRules.Claim(hide = setOf("dev"))
        // Device batteries waits hidden for its rule: the bar would not show it, the claiming app gets it.
        assertFalse(ItemsShareRules.shares(Section.HIDDEN, whenActive = true, active = false, gap = false))
        assertTrue(ItemsShareRules.sharesClaimed("dev", Section.HIDDEN, whenActive = true, active = false, gap = false, claim = claim))
        assertFalse(ClaimRules.inStrip("dev", claim))
        assertTrue(ClaimRules.inStrip("cpu", claim))
        // Listing them all: everything outside Off, never Off, never a gap.
        val watch = ClaimRules.Claim(watch = true)
        assertTrue(ItemsShareRules.sharesClaimed("x", Section.HIDDEN, whenActive = false, active = false, gap = false, claim = watch))
        assertFalse(ItemsShareRules.sharesClaimed("x", Section.OFF, whenActive = false, active = false, gap = false, claim = watch))
        assertFalse(ItemsShareRules.sharesClaimed("x", Section.SHOWN, whenActive = false, active = false, gap = true, claim = watch))
        assertTrue(ClaimRules.Claim().isEmpty)
        // The whole bar: the strip draws nothing, and the claim holds by itself.
        val whole = ClaimRules.Claim(whole = true)
        assertFalse(ClaimRules.inStrip("cpu", whole))
        assertFalse(whole.isEmpty)
    }

    @Test fun watchersHearOfNewWordsOnly() {
        assertTrue(ItemsShareRules.differs(null, listOf(cpu)))
        assertFalse(ItemsShareRules.differs(listOf(cpu), listOf(cpu.copy())))
        assertTrue(ItemsShareRules.differs(listOf(cpu), listOf(cpu.copy(text = "13%"))))
        assertTrue(ItemsShareRules.differs(listOf(cpu), emptyList()))
    }
}
