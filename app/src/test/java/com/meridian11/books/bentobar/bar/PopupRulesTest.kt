package com.meridian11.books.bentobar.bar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Switch popups on hover" (off by default): with a popup open, pointing at another item opens its popup, as in a menu bar. */
class PopupRulesTest {
    @Test fun withTheSettingOnPointingAtAnotherItemOpensItsPopup() {
        assertTrue(PopupRules.hoverOpens(open = "item:weather1", hovered = "calendar1", hasPopup = true, on = true))
        // From the ‹ menu too: it is the bar's own popup.
        assertTrue(PopupRules.hoverOpens(open = "bentobar", hovered = "calendar1", hasPopup = true, on = true))
    }

    @Test fun offByDefaultAndNeverWithoutAnOpenPopup() {
        assertFalse(PopupRules.hoverOpens(open = "item:weather1", hovered = "calendar1", hasPopup = true, on = false))
        // Nothing open: hovering never opens anything (Alex: no hover surprises).
        assertFalse(PopupRules.hoverOpens(open = null, hovered = "calendar1", hasPopup = true, on = true))
    }

    @Test fun itNeverActsForAClickAndLeavesARightClickMenuAlone() {
        // An item that acts on a click (keep awake, a Shortcut) has no popup: hovering it must not act.
        assertFalse(PopupRules.hoverOpens(open = "item:weather1", hovered = "caffeine1", hasPopup = false, on = true))
        // The item whose popup is open: nothing to switch.
        assertFalse(PopupRules.hoverOpens(open = "item:weather1", hovered = "weather1", hasPopup = true, on = true))
        // A right-click menu belongs to its item: pointing elsewhere doesn't swap it for a popup.
        assertFalse(PopupRules.hoverOpens(open = "ctx:weather1", hovered = "calendar1", hasPopup = true, on = true))
    }

    @Test fun aPopupStaysWithinTheStrip() {
        // The monitor's strip from 1845 to 2210, the battery item 1905 to 1980, a popup 300 px wide: it would stick out
        // past the strip's left end lined up with the item's right edge, so it starts where the strip does.
        assertEquals(1845, PopupRules.popupLeft(1905, 1980, 300, 1845, 2210, 2560, 32))
        // An item at the strip's right end: the popup's right edge with the item's.
        assertEquals(1900, PopupRules.popupLeft(2150, 2200, 300, 1845, 2210, 2560, 32))
        // Wider than the strip: it lines up with the strip's end on the item's side.
        assertEquals(1710, PopupRules.popupLeft(1905, 1980, 500, 1845, 2210, 2560, 32))
        assertEquals(100, PopupRules.popupLeft(120, 200, 500, 100, 400, 2560, 32))
        // In the left half, the left edges line up.
        assertEquals(300, PopupRules.popupLeft(300, 380, 400, 100, 1200, 2560, 32))
    }
}
