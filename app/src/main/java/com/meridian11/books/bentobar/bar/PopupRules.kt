package com.meridian11.books.bentobar.bar

/** When pointing at an item opens its popup. Pure, so the rules are tested (`PopupRulesTest`). */
internal object PopupRules {
    /**
     * Whether the pointer coming onto item [hovered] opens its popup: only with "Switch popups on hover" [on] (off by
     * default: nothing opens on hover by itself), only while an item's popup or the ‹ menu is [open] (a key as
     * `toggleMenu` has it: "item:ID", "ctx:ID", "bentobar"), never for the item already open, never for a right-click
     * menu, and only for an item that [hasPopup]: one that acts on a click (keep awake, a Shortcut) must not act on a hover.
     */
    fun hoverOpens(open: String?, hovered: String, hasPopup: Boolean, on: Boolean): Boolean {
        if (!on || open == null || !hasPopup) return false
        if (open != "bentobar" && !open.startsWith("item:")) return false
        return open != "item:$hovered"
    }

    /**
     * Where a popup [width] px wide goes across the screen, under (or over) an item from [itemLeft] to [itemRight], on a
     * strip from [stripLeft] to [stripRight]: in the screen's right half its right edge lines up with the item's, in the
     * left half its left edge; then it is kept within the strip, so it never strays past the strip's ends. A popup wider
     * than the strip lines up with the strip's end on the item's side. And always [margin] px inside the screen.
     */
    fun popupLeft(itemLeft: Int, itemRight: Int, width: Int, stripLeft: Int, stripRight: Int, screenWidth: Int, margin: Int): Int {
        val rightHalf = (itemLeft + itemRight) / 2 > screenWidth / 2
        val byItem = if (rightHalf) itemRight - width else itemLeft
        val inStrip = if (width <= stripRight - stripLeft) byItem.coerceIn(stripLeft, stripRight - width)
            else if (rightHalf) stripRight - width else stripLeft
        return inStrip.coerceIn(margin, (screenWidth - width - margin).coerceAtLeast(margin))
    }
}
