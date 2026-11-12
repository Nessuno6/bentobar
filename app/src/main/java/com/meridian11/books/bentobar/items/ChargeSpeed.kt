package com.meridian11.books.bentobar.items

import androidx.compose.ui.graphics.Color
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.util.Sym

/**
 * How fast the charger charges, in the steps a Samsung device tells apart, from what it adds to the battery broadcast:
 * `charger_type` (its kernel's charger status: 1 and 2 adaptive fast at 15 W and 20 W, 3 super fast at 25 W, 4 super
 * fast 2.0 at 45 W) and
 * `hv_charger` (a high-voltage charger, adaptive fast at least). Other devices send neither, and charge at [NORMAL].
 *
 * The step is the charger's and the cable's, agreed when plugged in: a 45 W charger gives its 45 W only while the battery
 * is low, but stays "2.0" all along, as on the device's own lock screen.
 */
enum class ChargeSpeed(val words: Int) {
    NORMAL(R.string.battery_speed_normal),
    FAST(R.string.battery_speed_fast),
    SUPER(R.string.battery_speed_super),
    SUPER_2(R.string.battery_speed_super_2);

    /** In the bar: one bolt, or two for super fast 2.0. */
    val glyph: String get() = if (this == SUPER_2) DOUBLE_BOLT else Sym.BOLT

    /** Green for normal and adaptive fast charging, blue for super fast. */
    val color: Color get() = if (this == SUPER || this == SUPER_2) BLUE else BatteryBand.GREEN

    companion object {
        fun of(chargerType: Int, hvCharger: Boolean): ChargeSpeed = when {
            chargerType >= 4 -> SUPER_2
            chargerType == 3 -> SUPER
            chargerType >= 1 || hvCharger -> FAST
            else -> NORMAL
        }

        /** Marks the bolt as the double one (an invisible character, as a charging device's glyph is marked). */
        const val DOUBLE = "⁡"
        const val DOUBLE_BOLT = Sym.BOLT + DOUBLE
        val BLUE = Color(0xFF3E91FF)
    }
}
