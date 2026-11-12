package com.meridian11.books.bentobar.widget

import com.meridian11.books.bentobar.items.DevicesRules
import com.meridian11.books.bentobar.items.DevicesRules.Ears
import com.meridian11.books.bentobar.items.LinkRules

/**
 * The home screen widget's rules, pure: which devices it has, in what order, which goes in which cell, how big its
 * grid is and what colour each arc takes. The widget (widget/Widgets.kt) draws what these say.
 */
object WidgetRules {
    /** Columns and rows of cells. */
    data class Grid(val cols: Int, val rows: Int) {
        val cells get() = cols * rows
    }

    enum class Kind { PHONE, TABLET, MOUSE, KEYBOARD, STYLUS, GAMEPAD, HEADPHONES, CASE, WATCH }

    /**
     * One device in the widget. [key] is what a pinned cell and the order remember it by. [percent]: null where its
     * level isn't known (a pinned device that is away). [seenAt]: when a case's level was read (wall clock), else 0.
     * [linked]: told of by the linked BentoBar (items/Link), not seen here.
     */
    data class Cell(
        val key: String,
        val name: String,
        val kind: Kind,
        val percent: Int?,
        val charging: Boolean,
        val ears: Ears? = null,
        val seenAt: Long = 0L,
        val linked: Boolean = false,
        /** Earbuds: which of them charge in their case; One UI marks each with a dot. */
        val chargingEars: Ears? = null,
    )

    /** A cell that follows the order ([AUTO]), stays empty ([EMPTY]), or holds a device's key. */
    const val AUTO = ""
    const val EMPTY = "-"
    const val MAX = 4
    const val SELF = "self"

    /** The arc's colour: green, and red from 15% down unless it is charging (One UI keeps a charging arc green). */
    enum class Band { GREEN, RED }

    fun band(c: Cell): Band = if (!c.charging && c.percent != null && c.percent <= 15) Band.RED else Band.GREEN

    fun kindOf(k: DevicesRules.Kind): Kind = when (k) {
        DevicesRules.Kind.MOUSE -> Kind.MOUSE
        DevicesRules.Kind.KEYBOARD -> Kind.KEYBOARD
        DevicesRules.Kind.STYLUS -> Kind.STYLUS
        DevicesRules.Kind.GAMEPAD -> Kind.GAMEPAD
        DevicesRules.Kind.HEADPHONES -> Kind.HEADPHONES
        DevicesRules.Kind.WATCH -> Kind.WATCH
        DevicesRules.Kind.PHONE -> Kind.PHONE
        DevicesRules.Kind.TABLET -> Kind.TABLET
    }

    /**
     * The dots under an earbuds icon, as One UI draws them for the earbuds that charge in their case: where each one sits
     * across the icon, from -1 (its left edge) to 1 (its right edge). Both buds drawn and both charging: two, one under
     * each; both drawn and one charging: under that one; one bud drawn: under it.
     */
    fun chargeDots(c: Cell): List<Float> {
        val charging = c.chargingEars ?: return emptyList()
        if (c.kind != Kind.HEADPHONES) return emptyList()
        return if (c.ears != Ears.BOTH) listOf(0f) else when (charging) {
            Ears.BOTH -> listOf(-0.5f, 0.5f)
            Ears.LEFT -> listOf(-0.5f)
            Ears.RIGHT -> listOf(0.5f)
            Ears.ONE -> listOf(0f)
        }
    }

    fun keyOf(kind: Kind, name: String) = "${kind.name.lowercase()}:$name"

    /**
     * Every device the widget can show, in its usual order: this device, then the others by kind (a case right after
     * its earbuds), then name. [devices] are Device batteries' own and the linked side's (their descriptors start with
     * [LinkRules.PREFIX]); [cases] are the cases as last seen, each named as the widget shows it.
     */
    fun cells(self: Cell?, devices: List<DevicesRules.Device>, cases: List<Cell>): List<Cell> {
        val seen = HashSet<String>()
        val others = ArrayList<Cell>()
        for (d in devices) {
            val kind = kindOf(d.kind)
            val key = keyOf(kind, d.name)
            if (d.name.isBlank() || !seen.add(key) || key == self?.let { keyOf(it.kind, it.name) }) continue
            others += Cell(key, d.name, kind, d.percent, d.charging, d.ears, linked = d.descriptor.startsWith(LinkRules.PREFIX),
                chargingEars = d.chargingEars)
        }
        others += cases.filter { seen.add(it.key) }
        fun rank(k: Kind) = if (k == Kind.CASE) Kind.HEADPHONES.ordinal else k.ordinal
        return listOfNotNull(self) + others.sortedWith(compareBy({ rank(it.kind) }, { it.name.lowercase() }))
    }

    /** [cells] in the user's order: the keys in [priority] first, as listed, then the rest as they were. */
    fun ordered(cells: List<Cell>, priority: List<String>): List<Cell> {
        val rank = priority.withIndex().associate { it.value to it.index }
        return cells.withIndex().sortedWith(compareBy({ rank[it.value.key] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }

    /**
     * What each of [count] cells holds: a pinned device where one is pinned (as last known, with no level, while it is
     * away), the next of [visible] not pinned elsewhere in each [AUTO] cell, nothing in an [EMPTY] one.
     */
    fun assign(slots: List<String>, count: Int, visible: List<Cell>, all: List<Cell>, known: Map<String, Cell>): List<Cell?> {
        val pinned = slots.filter { it != AUTO && it != EMPTY }.toSet()
        val pool = visible.filter { it.key !in pinned }.iterator()
        return (0 until count).map { i ->
            when (val k = slots.getOrNull(i) ?: AUTO) {
                AUTO -> if (pool.hasNext()) pool.next() else null
                EMPTY -> null
                else -> all.firstOrNull { it.key == k } ?: known[k]?.copy(percent = null, charging = false)
            }
        }
    }

    /** The 4 x 2 widget: one row when it is resized to a single cell high. */
    fun mainGrid(minHeightDp: Int) = Grid(MAX, if (minHeightDp in 1..99) 1 else 2)

    /** [n] devices: a row, or a column when the widget is resized taller than wide. */
    fun stripGrid(n: Int, widthDp: Int, heightDp: Int) =
        if (n > 1 && widthDp > 0 && heightDp > widthDp * 1.5f) Grid(1, n) else Grid(n, 1)
}
