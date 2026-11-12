package com.meridian11.books.bentobar.util

import android.content.Context

/**
 * One UI's own icons in place of some Material Symbols, where a build has them: vector drawables named `oneui_<symbol>`
 * that `tools/oneui_icons.py` writes from One UI's SVGs. They are Samsung's designs, kept out of the repository; without
 * them every symbol is drawn from the Material Symbols font, as before.
 */
object OneUiIcons {
    /** The symbols One UI has an icon for, by their glyph, as `tools/oneui_icons.py` names the drawables. */
    private val names = mapOf(
        Sym.MOUSE to "mouse", Sym.KEYBOARD to "keyboard", Sym.STYLUS to "stylus", Sym.SPORTS_ESPORTS to "sports_esports",
        Sym.HEADPHONES to "headphones", Sym.BOLT to "bolt", Sym.SETTINGS to "settings", Sym.EDIT to "edit",
        Sym.POWER_SETTINGS_NEW to "power_settings_new", Sym.ALARM to "alarm", Sym.MUSIC_NOTE to "music_note",
        Sym.BLUETOOTH to "bluetooth", Sym.WIFI to "wifi", Sym.NOTIFICATIONS to "notifications",
        Sym.SCREENSHOT_MONITOR to "screenshot_monitor", Sym.VIDEOCAM to "videocam", Sym.DESKTOP_WINDOWS to "desktop_windows",
        Sym.KEY to "key", Sym.WATCH to "watch", Sym.SMARTPHONE to "smartphone", Sym.TABLET_ANDROID to "tablet_android",
        // Super fast charging 2.0 (items/ChargeSpeed).
        com.meridian11.books.bentobar.items.ChargeSpeed.DOUBLE_BOLT to "bolt_double",
        // A linked phone or tablet on its charger (items/DevicesRules.chargingGlyph).
        Sym.SMARTPHONE + com.meridian11.books.bentobar.items.DevicesRules.CHARGING to "smartphone_charging",
        Sym.TABLET_ANDROID + com.meridian11.books.bentobar.items.DevicesRules.CHARGING to "tablet_android_charging",
        Sym.SMARTPHONE + com.meridian11.books.bentobar.items.DevicesRules.CHARGING_2 to "smartphone_charging_2",
        Sym.TABLET_ANDROID + com.meridian11.books.bentobar.items.DevicesRules.CHARGING_2 to "tablet_android_charging_2",
        // Earbuds as they are connected (items/DevicesRules.glyph): both, the left one, the right one.
        com.meridian11.books.bentobar.items.DevicesRules.EARBUDS_BOTH to "headphones_both",
        com.meridian11.books.bentobar.items.DevicesRules.EARBUDS_LEFT to "headphones_left",
        com.meridian11.books.bentobar.items.DevicesRules.EARBUDS_RIGHT to "headphones_right", Sym.VOLUME_UP to "volume_up", Sym.HANDYMAN to "handyman", Sym.LOCK to "lock", Sym.SWAP_VERT to "swap_vert",
    )

    @Volatile private var ids: Map<String, Int>? = null

    /** The drawables this build has, found once by name (0: not in this build). */
    private fun ids(context: Context): Map<String, Int> = ids ?: names.mapNotNull { (glyph, name) ->
        @Suppress("DiscouragedApi")
        context.resources.getIdentifier("oneui_$name", "drawable", context.packageName).takeIf { it != 0 }?.let { glyph to it }
    }.toMap().also { ids = it }

    /** The drawable for [glyph], or 0 where One UI has none in this build. */
    fun drawable(context: Context, glyph: String): Int = ids(context)[glyph] ?: 0

    /** Whether this build has any One UI icons (the Look page offers the switch only then). */
    fun available(context: Context): Boolean = ids(context).isNotEmpty()
}
