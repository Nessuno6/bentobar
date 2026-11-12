package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.DevicesRules.Case
import com.meridian11.books.bentobar.items.DevicesRules.Device
import com.meridian11.books.bentobar.items.DevicesRules.Ears

/**
 * What other apps read of Device batteries ([DevicesProvider]), as tables: pure, so the columns and their values are
 * pinned by tests. These names are a promise to other apps' code: add columns, never rename or drop one.
 *
 * `devices`: the devices found on this device, one row each. `cases`: the earbuds' cases as last seen.
 */
object DevicesShareRules {
    /** Raised when the tables change in a way an app must check for; adding a column is not one. */
    const val VERSION = 1

    /** How long a reading taken for another app is handed out again, before the devices are read anew. */
    const val FRESH_MS = 10_000L

    enum class Table(val path: String) { DEVICES("devices"), CASES("cases") }

    /** The table a content URI's path names, or null. */
    fun table(path: List<String>): Table? = Table.entries.firstOrNull { path.size == 1 && path[0] == it.path }

    /**
     * `kind`: mouse, keyboard, stylus, gamepad, headphones, watch, phone or tablet. `name`: as the device calls itself,
     * can be empty. `percent`: 0–100, null where it has a battery that doesn't say how full. `charging`: 1 or 0.
     * `ears`: for earbuds, which are out: both, left, right or one (a single bud whose side isn't known); null otherwise.
     */
    val DEVICE_COLUMNS = arrayOf("kind", "name", "percent", "charging", "ears")

    /** `name`: the earbuds' name. `percent`: 1–100. `seen_at`: when that level was read, wall-clock milliseconds. */
    val CASE_COLUMNS = arrayOf("name", "percent", "seen_at")

    fun columns(t: Table): Array<String> = when (t) {
        Table.DEVICES -> DEVICE_COLUMNS
        Table.CASES -> CASE_COLUMNS
    }

    fun row(d: Device): Array<Any?> = arrayOf(
        d.kind.name.lowercase(),
        d.name,
        d.percent?.coerceIn(0, 100),
        if (d.charging) 1 else 0,
        d.ears?.let { ears(it) },
    )

    fun row(c: Case): Array<Any?> = arrayOf(c.name, c.level, c.at)

    private fun ears(e: Ears) = when (e) {
        Ears.BOTH -> "both"
        Ears.LEFT -> "left"
        Ears.RIGHT -> "right"
        Ears.ONE -> "one"
    }

    /** Whether what is shared changed, so that apps watching the tables are told: rows, not reading times. */
    fun differs(before: List<Device>?, now: List<Device>): Boolean =
        before == null || before.map { row(it).toList() } != now.map { row(it).toList() }
}
