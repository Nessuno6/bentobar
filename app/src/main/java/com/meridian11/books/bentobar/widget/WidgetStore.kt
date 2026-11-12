package com.meridian11.books.bentobar.widget

import android.content.Context
import com.meridian11.books.bentobar.items.DevicesRules.Ears
import com.meridian11.books.bentobar.widget.WidgetRules.Cell
import com.meridian11.books.bentobar.widget.WidgetRules.Grid
import com.meridian11.books.bentobar.widget.WidgetRules.Kind

/**
 * The widgets' settings, in BentoBar's own preferences: each widget's cells (and a custom one's grid), and for all of
 * them the order devices fill cells in, the devices kept out, the text colour, and the devices seen before (so a cell
 * pinned to one that is away still shows its icon). Names are kept, never addresses.
 */
object WidgetStore {
    /** The text's colour: [SYSTEM] black in light mode and white in dark mode (the default), [AUTO] by the wallpaper. */
    enum class Text { AUTO, DARK, LIGHT, SYSTEM }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun slots(ctx: Context, id: Int): List<String> = sp(ctx).getString("slots_$id", null)?.split(SEP) ?: emptyList()

    fun setSlots(ctx: Context, id: Int, slots: List<String>) =
        sp(ctx).edit().putString("slots_$id", slots.joinToString(SEP) { it.replace(SEP, "") }).apply()

    fun grid(ctx: Context, id: Int) = Grid(
        sp(ctx).getInt("cols_$id", 2).coerceIn(1, WidgetRules.MAX),
        sp(ctx).getInt("rows_$id", 1).coerceIn(1, WidgetRules.MAX),
    )

    fun setGrid(ctx: Context, id: Int, g: Grid) = sp(ctx).edit().putInt("cols_$id", g.cols).putInt("rows_$id", g.rows).apply()

    fun delete(ctx: Context, id: Int) = sp(ctx).edit().remove("slots_$id").remove("cols_$id").remove("rows_$id").apply()

    fun priority(ctx: Context): List<String> = sp(ctx).getString("priority", "")!!.split(SEP).filter { it.isNotEmpty() }

    fun setPriority(ctx: Context, keys: List<String>) =
        sp(ctx).edit().putString("priority", keys.joinToString(SEP) { it.replace(SEP, "") }).apply()

    fun hidden(ctx: Context): Set<String> = sp(ctx).getStringSet("hidden", emptySet())!!

    fun setHidden(ctx: Context, key: String, hide: Boolean) =
        sp(ctx).edit().putStringSet("hidden", hidden(ctx).toMutableSet().apply { if (hide) add(key) else remove(key) }).apply()

    fun text(ctx: Context): Text = Text.entries.getOrElse(sp(ctx).getInt("text", Text.SYSTEM.ordinal)) { Text.SYSTEM }

    fun setText(ctx: Context, t: Text) = sp(ctx).edit().putInt("text", t.ordinal).apply()

    /** The devices seen before, by key, with no level. */
    fun known(ctx: Context): Map<String, Cell> = sp(ctx).getStringSet("known", emptySet())!!.mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 4) return@mapNotNull null
        val kind = Kind.entries.firstOrNull { it.name == f[2] } ?: return@mapNotNull null
        f[0] to Cell(f[0], f[1], kind, null, false, Ears.entries.firstOrNull { it.name == f[3] })
    }.toMap()

    /** Adds [cells] to the devices seen; writes only when one is new or changed what it is. */
    fun remember(ctx: Context, cells: List<Cell>) {
        val known = known(ctx)
        if (cells.none { c -> known[c.key].let { it == null || it.name != c.name || it.kind != c.kind || it.ears != c.ears } }) return
        write(ctx, known + cells.associateBy { it.key })
    }

    fun forget(ctx: Context, key: String) = write(ctx, known(ctx) - key)

    private fun write(ctx: Context, all: Map<String, Cell>) {
        fun clean(s: String) = s.replace(Regex("[\\t\\r\\n]+"), " ")
        sp(ctx).edit().putStringSet("known", all.values.map { "${clean(it.key)}\t${clean(it.name)}\t${it.kind.name}\t${it.ears?.name ?: "-"}" }.toSet()).apply()
    }

    private const val SEP = "|"
}
