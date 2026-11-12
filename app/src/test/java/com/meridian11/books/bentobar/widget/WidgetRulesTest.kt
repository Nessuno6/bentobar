package com.meridian11.books.bentobar.widget

import com.meridian11.books.bentobar.items.DevicesRules.Device
import com.meridian11.books.bentobar.items.DevicesRules.Ears
import com.meridian11.books.bentobar.items.DevicesRules.Kind as DeviceKind
import com.meridian11.books.bentobar.items.LinkRules
import com.meridian11.books.bentobar.widget.WidgetRules.Band
import com.meridian11.books.bentobar.widget.WidgetRules.Cell
import com.meridian11.books.bentobar.widget.WidgetRules.Grid
import com.meridian11.books.bentobar.widget.WidgetRules.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home screen widget: its devices, their order, which goes in which cell, its grid and its colours. */
class WidgetRulesTest {
    private val self = Cell(WidgetRules.SELF, "Phone", Kind.PHONE, 77, false)
    private val mouse = Device("d1", "Pebble", DeviceKind.MOUSE, 95, false)
    private val buds = Device("d2", "Buds", DeviceKind.HEADPHONES, 54, false, Ears.BOTH)
    private val tablet = Device("${LinkRules.PREFIX}0", "Tablet", DeviceKind.TABLET, 53, true)
    private val case = Cell("case:Buds", "Buds case", Kind.CASE, 100, false, seenAt = 1L)

    @Test fun thisDeviceComesFirstThenTheOthersByKindWithACaseAfterItsBuds() {
        val cells = WidgetRules.cells(self, listOf(buds, mouse, tablet), listOf(case))
        assertEquals(listOf("self", "tablet:Tablet", "mouse:Pebble", "headphones:Buds", "case:Buds"), cells.map { it.key })
    }

    @Test fun theLinkedSideIsMarked() {
        val cells = WidgetRules.cells(self, listOf(mouse, tablet), emptyList())
        assertTrue(cells.single { it.kind == Kind.TABLET }.linked)
        assertFalse(cells.single { it.kind == Kind.MOUSE }.linked)
    }

    @Test fun aDeviceIsListedOnceAndNamelessOnesNotAtAll() {
        val cells = WidgetRules.cells(self, listOf(mouse, mouse.copy(descriptor = "d9"), mouse.copy(name = "")), emptyList())
        assertEquals(listOf("self", "mouse:Pebble"), cells.map { it.key })
    }

    @Test fun theUsersOrderComesFirst() {
        val cells = WidgetRules.cells(self, listOf(buds, mouse), emptyList())
        val ordered = WidgetRules.ordered(cells, listOf("headphones:Buds", "gone:Away"))
        assertEquals(listOf("headphones:Buds", "self", "mouse:Pebble"), ordered.map { it.key })
    }

    @Test fun cellsArePinnedEmptyOrFilledInOrder() {
        val all = WidgetRules.cells(self, listOf(buds, mouse), emptyList())
        val known = mapOf("watch:Watch" to Cell("watch:Watch", "Watch", Kind.WATCH, 40, true))
        val got = WidgetRules.assign(listOf("mouse:Pebble", WidgetRules.EMPTY, WidgetRules.AUTO, "watch:Watch"), 5, all, all, known)
        assertEquals("mouse:Pebble", got[0]?.key)
        assertNull(got[1])
        // The auto cells skip the pinned mouse.
        assertEquals("self", got[2]?.key)
        // A pinned device that is away keeps its cell, with no level.
        assertEquals("watch:Watch", got[3]?.key)
        assertNull(got[3]?.percent)
        assertFalse(got[3]!!.charging)
        assertEquals("headphones:Buds", got[4]?.key)
    }

    @Test fun aHiddenDeviceStaysOutOfAutoCellsButNotOutOfPinnedOnes() {
        val all = WidgetRules.cells(self, listOf(mouse), emptyList())
        val visible = all.filter { it.key != "mouse:Pebble" }
        assertEquals(listOf("self", null), WidgetRules.assign(emptyList(), 2, visible, all, emptyMap()).map { it?.key })
        assertEquals("mouse:Pebble", WidgetRules.assign(listOf("mouse:Pebble"), 1, visible, all, emptyMap())[0]?.key)
    }

    @Test fun theArcIsGreenAndRedFrom15DownUnlessCharging() {
        fun band(p: Int?, charging: Boolean = false) = WidgetRules.band(Cell("k", "n", Kind.MOUSE, p, charging))
        assertEquals(Band.RED, band(15))
        assertEquals(Band.RED, band(3))
        assertEquals(Band.GREEN, band(16))
        assertEquals(Band.GREEN, band(20))
        // Charging keeps One UI's green, however low.
        assertEquals(Band.GREEN, band(5, charging = true))
        assertEquals(Band.GREEN, band(null))
    }

    @Test fun aDotSitsUnderEachEarbudThatCharges() {
        val both = Cell("k", "Buds", Kind.HEADPHONES, 65, true, ears = Ears.BOTH)
        assertEquals(listOf(-0.5f, 0.5f), WidgetRules.chargeDots(both.copy(chargingEars = Ears.BOTH)))
        assertEquals(listOf(-0.5f), WidgetRules.chargeDots(both.copy(chargingEars = Ears.LEFT)))
        assertEquals(listOf(0.5f), WidgetRules.chargeDots(both.copy(chargingEars = Ears.RIGHT)))
        // One bud drawn: its dot is under it.
        assertEquals(listOf(0f), WidgetRules.chargeDots(both.copy(ears = Ears.LEFT, chargingEars = Ears.LEFT)))
        // Nothing charging, or not earbuds: no dots.
        assertEquals(emptyList<Float>(), WidgetRules.chargeDots(both))
        assertEquals(emptyList<Float>(), WidgetRules.chargeDots(Cell("m", "Mouse", Kind.MOUSE, 50, true, chargingEars = Ears.BOTH)))
    }

    @Test fun earbudsBringWhichOfThemChargeIntoTheirCell() {
        val charging = buds.copy(charging = true, chargingEars = Ears.BOTH)
        assertEquals(Ears.BOTH, WidgetRules.cells(self, listOf(charging), emptyList()).single { it.kind == Kind.HEADPHONES }.chargingEars)
    }

    @Test fun theGrids() {
        assertEquals(Grid(4, 2), WidgetRules.mainGrid(180))
        assertEquals(Grid(4, 1), WidgetRules.mainGrid(80))
        assertEquals(Grid(4, 2), WidgetRules.mainGrid(0))
        assertEquals(Grid(3, 1), WidgetRules.stripGrid(3, 300, 100))
        assertEquals(Grid(1, 3), WidgetRules.stripGrid(3, 100, 300))
        assertEquals(Grid(1, 1), WidgetRules.stripGrid(1, 100, 300))
    }
}
