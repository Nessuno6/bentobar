package com.meridian11.books.bentobar.widget

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import com.meridian11.books.bentobar.items.DevicesRules
import com.meridian11.books.bentobar.items.DevicesRules.Ears
import com.meridian11.books.bentobar.util.OneUiIcons
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.widget.WidgetRules.Band
import com.meridian11.books.bentobar.widget.WidgetRules.Cell
import com.meridian11.books.bentobar.widget.WidgetRules.Kind

/**
 * Draws one cell of the widget as a bitmap: an arc open at the bottom that fills with the level, the device's icon in
 * it and the level under it, with a bolt while charging. The icon is One UI's where the build has it (util/OneUiIcons),
 * else the filled Material Symbol.
 */
object WidgetArc {
    // The geometry is One UI's battery widget's, measured on its pixels against the arc's outer radius (78 here): the
    // stroke a quarter of it, an arc of 227° with its gap at the bottom, the icon centred in it, and the level's digits
    // 0.30 of the radius tall, from 0.62 to 0.92 of it below the centre.
    const val W = 220
    const val H = 178
    private const val CX = 110f
    private const val CY = 92f
    private const val RADIUS = 68f
    private const val STROKE = 20f
    private const val START = 156.5f
    private const val SWEEP = 227f
    private const val TEXT = 34f
    private const val BASELINE = CY + 72f
    private const val BOLT = 30f
    private const val ICON = 32f
    /** The radius of a charging earbud's dot. */
    private const val DOT = 5.5f
    /** How much wider than tall an icon may be drawn inside the arc. */
    private const val WIDE = 1.5f

    // One UI's own green, as its battery widget draws it (read from its pixels): lighter at the arc's start, shading to
    // the deeper green over its first third, and that green from there on. The screen works in Display P3 and these are
    // P3 values: given as sRGB they came out duller (#58BF6F showed as #73BD77), so the coloured layer is drawn in P3.
    private val P3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
    private val GREEN_START = p3(0x83, 0xC8, 0x5D)
    private val GREEN_END = p3(0x58, 0xBF, 0x6F)

    private fun p3(r: Int, g: Int, b: Int): Long = Color.pack(r / 255f, g / 255f, b / 255f, 1f, P3)
    private const val GREEN_SPAN = 120f
    private val RED = Color.parseColor("#F0533F")
    /** The track: the text's colour, faint. */
    private const val TRACK = 0x33FFFFFF

    /** The case has no symbol of its own: One UI's case where the build has it, else the earbuds' symbol. */
    private const val CASE_DRAWABLE = "oneui_headphones_case"

    @Volatile private var symbols: Typeface? = null

    private fun symbols(ctx: Context): Typeface =
        symbols ?: Typeface.createFromAsset(ctx.assets, "fonts/MaterialSymbolsRounded_Fill.ttf").also { symbols = it }

    fun glyph(c: Cell): String = when (c.kind) {
        Kind.PHONE -> Sym.SMARTPHONE
        Kind.TABLET -> Sym.TABLET_ANDROID
        Kind.MOUSE -> Sym.MOUSE
        Kind.KEYBOARD -> Sym.KEYBOARD
        Kind.STYLUS -> Sym.STYLUS
        Kind.GAMEPAD -> Sym.SPORTS_ESPORTS
        Kind.WATCH -> Sym.WATCH
        Kind.CASE -> Sym.HEADPHONES
        Kind.HEADPHONES -> when (c.ears) {
            Ears.BOTH -> DevicesRules.EARBUDS_BOTH
            Ears.LEFT -> DevicesRules.EARBUDS_LEFT
            Ears.RIGHT, Ears.ONE -> DevicesRules.EARBUDS_RIGHT
            null -> Sym.HEADPHONES
        }
    }

    /** Whether the widget's text and icons are dark: as the user chose, or as the wallpaper suits. */
    fun darkText(ctx: Context): Boolean = when (WidgetStore.text(ctx)) {
        WidgetStore.Text.DARK -> true
        WidgetStore.Text.LIGHT -> false
        WidgetStore.Text.SYSTEM -> !night(ctx)
        WidgetStore.Text.AUTO -> runCatching {
            val colors = WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            colors != null && colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
        }.getOrDefault(false)
    }

    /**
     * The layer in the text's colour: the track, the icon, the level and a bolt while charging, all drawn white, for
     * the widget to tint black or white (so the launcher can follow the night mode without a new drawing).
     */
    fun foreground(ctx: Context, c: Cell?): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawArc(oval(), START, SWEEP, false, stroke().apply { color = TRACK })
        if (c == null) return bmp
        val fg = Color.WHITE
        icon(ctx, canvas, c, fg, CX, CY, ICON)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg
            textSize = TEXT
            typeface = Typeface.create(Typeface.DEFAULT, 400, false)
        }
        val label = c.percent?.toString() ?: "–"
        val bolt = if (c.charging) BOLT else 0f
        var x = CX - (bolt + text.measureText(label)) / 2
        val baseline = BASELINE
        if (c.charging) {
            icon(ctx, canvas, null, fg, x + bolt / 2 - 3, baseline - 12, bolt / 2, Sym.BOLT)
            x += bolt - 3
        }
        canvas.drawText(label, x, baseline, text)
        return bmp
    }

    /** The coloured layer: the arc as far as the level goes, in its band's colour. Empty without a level. */
    fun colour(c: Cell?): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888, true, P3)
        // A green dot under each earbud that charges in its case, as One UI marks them.
        if (c != null) {
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { setColor(GREEN_END) }
            for (at in WidgetRules.chargeDots(c)) Canvas(bmp).drawCircle(CX + at * ICON, CY + 30f, DOT, dot)
        }
        val percent = c?.percent ?: return bmp
        if (percent <= 0) return bmp
        val fill = stroke().apply {
            when (WidgetRules.band(c)) {
                Band.RED -> color = RED
                Band.GREEN -> shader = green()
            }
        }
        Canvas(bmp).drawArc(oval(), START, SWEEP * percent / 100f, false, fill)
        return bmp
    }

    /** Both layers as one, the first in black ([dark]) or white: for the settings screen's previews. */
    fun night(ctx: Context) =
        ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun render(ctx: Context, c: Cell?, dark: Boolean): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val tint = Paint().apply {
            colorFilter = android.graphics.PorterDuffColorFilter(if (dark) Color.BLACK else Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(foreground(ctx, c), 0f, 0f, tint)
        canvas.drawBitmap(colour(c), 0f, 0f, null)
        return out
    }

    /** The green along the arc: a sweep turned to start where the arc does. */
    private fun green(): Shader = SweepGradient(CX, CY, longArrayOf(GREEN_START, GREEN_END, GREEN_END), floatArrayOf(0f, GREEN_SPAN / 360f, 1f))
        .apply { setLocalMatrix(Matrix().apply { setRotate(START, CX, CY) }) }

    private fun oval() = RectF(CX - RADIUS, CY - RADIUS, CX + RADIUS, CY + RADIUS)

    private fun stroke() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** [glyph] (or [c]'s) centred on ([cx], [cy]), [half] wide each way, in [color]. */
    private fun icon(ctx: Context, canvas: Canvas, c: Cell?, color: Int, cx: Float, cy: Float, half: Float, glyph: String = glyph(c!!)) {
        @Suppress("DiscouragedApi")
        fun named(name: String) = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
        // One UI's battery widget's own icons first where the build has them (filled, unlike the Bluetooth list's), then
        // the One UI icons BentoBar's bar uses, then the Material Symbol.
        val widget = when (c?.kind) {
            Kind.PHONE -> "oneui_widget_phone"
            Kind.TABLET -> "oneui_widget_tablet"
            Kind.WATCH -> "oneui_widget_watch"
            Kind.STYLUS -> "oneui_widget_stylus"
            Kind.CASE -> CASE_DRAWABLE
            else -> null
        }
        val id = widget?.let { named(it) }?.takeIf { it != 0 } ?: OneUiIcons.drawable(ctx, glyph)
        if (id != 0) {
            val d = ctx.getDrawable(id)?.mutate() ?: return
            d.setTint(color)
            // In its own proportions: One UI's icons aren't all square (both earbuds are wider than tall). A square one
            // fills 2 x [half]; a wider one may take up to [WIDE] times that across, as the arc leaves room for.
            val aspect = d.intrinsicWidth.toFloat() / d.intrinsicHeight.coerceAtLeast(1)
            val w = minOf(2 * half * aspect, 2 * half * WIDE)
            val h = w / aspect
            d.setBounds((cx - w / 2).toInt(), (cy - h / 2).toInt(), (cx + w / 2).toInt(), (cy + h / 2).toInt())
            d.draw(canvas)
            return
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = symbols(ctx)
            textSize = half * 2
            textAlign = Paint.Align.CENTER
        }
        val m = p.fontMetrics
        canvas.drawText(glyph, cx, cy - (m.ascent + m.descent) / 2, p)
    }
}
