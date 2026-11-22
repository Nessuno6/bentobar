package com.meridian11.books.bentobar.bar

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.data.ColorMode
import com.meridian11.books.bentobar.data.Pill
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.data.TextSize
import com.meridian11.books.bentobar.data.spacingDp

/**
 * The strip's colours: when the status bar is read ([ColorWatch] says), what a reading makes of the strip's look,
 * and the theme and wallpaper hints when there is no reading. The screenshot itself is asked for through [shoot], at
 * the accessibility edge ([StatusBarScan.shoot]): this class names none of that API.
 */
internal class ColorReader(
    private val context: Context,
    private val main: Handler,
    private val facts: Facts,
    private val updateLiveLook: Boolean = true,
    private val tag: String = BarService.TAG,
    private val shoot: (BarSnapshot, (BarColors?, Int?) -> Unit) -> Unit,
) {
    /** What the reader asks of the strip's controller, each time it needs it. */
    interface Facts {
        val started: Boolean
        val onScreen: Boolean
        /** No strip is on view: none is measured with something to draw, so every window of ours is invisible. No reading then. */
        val empty: Boolean
        val interactive: Boolean
        val snapshot: BarSnapshot?
    }

    private val watch = ColorWatch()
    private var sampled: BarColors? = null
    private var lastColor: ColorMode? = null
    private val sample = Runnable { sampleColor() }

    /** Read by the strip's composition. */
    val look = mutableStateOf(StripLook(Color.White, true, TextSize.DEFAULT, 10.dp, Pill.NONE))

    // A live wallpaper can report new colours every second: a quick cause, two readings.
    private val wallpaper = WallpaperManager.OnColorsChangedListener { _, _ -> readSoon(ColorWatch.Cause.QUICK, 300) }

    fun start() {
        runCatching { WallpaperManager.getInstance(context).addOnColorsChangedListener(wallpaper, main) }
        armChecks()
    }

    fun stop() {
        runCatching { WallpaperManager.getInstance(context).removeOnColorsChangedListener(wallpaper) }
        main.removeCallbacks(sample)
        main.removeCallbacks(keepChecking)
        nextCheck = Long.MAX_VALUE
    }

    /** The layout changed: colour back to "Match the status bar" (or its opposite) means reading the bar again (nothing else would until it changes). */
    fun onConfig() {
        Store.config.value.color.let { if (it != lastColor) { if (it.reads && lastColor != null) readSoon(ColorWatch.Cause.QUICK, 100); lastColor = it } }
        armChecks()
    }

    /**
     * Asks for colour readings after something that can change the bar's look. A reading is a
     * screenshot of SystemUI's status bar WINDOW only (its own surface, no app content), read at the
     * clock ([BarPixels]). On some devices that surface is glyphs on transparent; on others it's opaque
     * while an app is maximized (white glyphs on black) and see-through over the wallpaper otherwise.
     *
     * The bar gives no sign when it changes, and it changes late, so one cause is followed by a few
     * readings spread over the time the bar may take ([ColorWatch]): two within a second and a half
     * after a change of windows or of the wallpaper, six over ten seconds when the strip comes back on
     * screen or the theme or the display changed. Causes that keep coming are held to a budget there.
     *
     * Readings are taken only while the strip is on screen; a cause while it's hidden waits until the
     * strip shows ([BarController] asks then). The causes are the moments the bar can change: a new status bar
     * window, the strip coming back on screen, a window settling against the bar's lower edge or under
     * it, or leaving ([BarController]'s look at the windows), a theme, display or wallpaper change, and switching back to "Match the
     * status bar". Never on a timer: an accessibility service taking screenshots every 30 s looks like
     * screen capture to Android's threat detection.
     */
    fun readSoon(cause: ColorWatch.Cause, firstMs: Long) {
        if (!facts.started || !facts.onScreen) return
        // Not while no strip is on view (nothing to draw, or not measured yet): the reading waits until one is ([ColorWatch.pause]).
        if (facts.empty) return watch.owe(cause)
        watch.cause(cause, SystemClock.uptimeMillis(), firstMs)
        arm()
    }

    /** A window settled against the bar or under it, or left. */
    fun windowsMoved() { watch.windowsMoved(SystemClock.uptimeMillis()); arm() }

    /** The strip left the screen: what was still to be read no longer matters, and its return asks again. */
    fun stripLeft() { watch.clear(); main.removeCallbacks(sample) }

    /** No strip on view any more: no readings; one on view again: the readings owed meanwhile, or two quick ones. */
    fun contentChanged(empty: Boolean) {
        if (!facts.started || !facts.onScreen) return
        if (empty) watch.pause() else watch.resume(SystemClock.uptimeMillis(), 250)
        arm()
    }

    /** Times the next reading [watch] has to come, if any. */
    private fun arm() {
        main.removeCallbacks(sample)
        watch.due(SystemClock.uptimeMillis())?.let { main.postDelayed(sample, it) }
    }

    /** A reading out of turn, for the test hook. */
    fun sampleNow() = sampleColor()

    /** A reading; [check] when it is "Keep checking the status bar"'s, not one a cause asked for. */
    private fun sampleColor(check: Boolean = false) {
        if (!facts.started || !facts.onScreen || !facts.interactive) return watch.clear()
        if (facts.empty) return watch.pause()
        val s = facts.snapshot
        if (s == null || !Store.config.value.color.reads) { watch.clear(); return applyLook() }
        watch.taken(SystemClock.uptimeMillis(), check)
        shoot(s) { colors, failed ->
            // A result can arrive after stop(): it mustn't paint a strip that's gone.
            if (failed == null) main.post { if (facts.started) onSample(colors, check) }
            else main.post { if (facts.started) { if (!check) Log.i(tag, "status bar colour sample failed ($failed)"); onSample(null, check) } }
        }
    }

    /**
     * "Keep checking the status bar": the bar changes its colours with no event (an app turning its page dark, a
     * system that tints the bar its own way), so while the strip shows and reads the bar, it is read on a timer
     * whenever no series a cause asked for is under way: every second for a while after anything moved, every four
     * seconds once all is still ([ColorWatch.checkEvery]).
     */
    private val keepChecking = object : Runnable {
        override fun run() {
            nextCheck = Long.MAX_VALUE
            if (!checking()) return
            val now = SystemClock.uptimeMillis()
            if (facts.onScreen && !facts.empty && facts.interactive && watch.idle(now)) sampleColor(check = true)
            val next = ColorWatch.checkEvery(now - stirred)
            main.postDelayed(this, next); nextCheck = now + next
        }
    }

    /** When something last moved that can change the bar's look. */
    private var stirred = 0L
    private var nextCheck = Long.MAX_VALUE

    private fun checking() = Store.config.value.let { facts.started && it.keepChecking && it.color.reads }

    /** Something moved: quick checks for a while, the next one [firstMs] from now unless one is due sooner. */
    fun stir(firstMs: Long) {
        val now = SystemClock.uptimeMillis()
        stirred = now
        if (!checking() || now + firstMs >= nextCheck) return
        main.removeCallbacks(keepChecking)
        main.postDelayed(keepChecking, firstMs); nextCheck = now + firstMs
    }

    /** Starts or stops [keepChecking] as the settings say. */
    private fun armChecks() {
        main.removeCallbacks(keepChecking); nextCheck = Long.MAX_VALUE
        if (checking()) stir(ColorWatch.CHECK_FAST_MS)
    }

    /**
     * A reading, or null for none (the screenshot failed, or the bar was caught fading and had nothing
     * opaque to read). No reading keeps the colours the strip has; after three in a row the strip uses
     * the theme and wallpaper hints rather than a reading of how the bar looked before. [watch] says
     * whether another reading follows.
     */
    private fun onSample(c: BarColors?, check: Boolean = false) {
        val result = ColorWatch.result(sampled, c)
        if (result == ColorWatch.Result.CHANGED) { sampled = c; applyLook(); stirred = SystemClock.uptimeMillis() }
        // The strip left the screen while the screenshot was on its way: its return asks again.
        if (!facts.onScreen) return
        // A check that saw the same, or nothing (the next check comes anyway, and three of them mustn't make the
        // strip guess from the wallpaper): nothing to do or log. A change is followed up as any reading's is.
        if (check && result != ColorWatch.Result.CHANGED) return
        if (watch.read(SystemClock.uptimeMillis(), result)) {
            // With checks running, a bar read well before keeps its colours: a guess from the theme and the wallpaper was
            // the wrong colour as often as not, and the next check that reads something puts the strip right anyway.
            if (Store.config.value.keepChecking && sampled != null) Log.i(tag, "no status bar colour reading, keeping the last one")
            else { Log.i(tag, "no status bar colour reading, using theme and wallpaper hints"); sampled = null; applyLook() }
        }
        arm()
        // Every reading is logged: a strip in the wrong colour can then be traced in a device's log.
        Log.i(tag, "read${if (check) " (check)" else ""} ${c?.let { "text=${it.text?.let(::hex)} bg=${it.background?.let(::hex)}" } ?: "nothing"} (${result.name.lowercase()}), " +
            (watch.due(SystemClock.uptimeMillis())?.let { "next in $it ms" } ?: "no more"))
    }

    /** No sample: a dark system theme means a dark bar on Googlebooks; otherwise the wallpaper decides. */
    private fun fallbackText(): Color {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (night) return Color.White
        val hints = runCatching { WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.colorHints }.getOrNull() ?: 0
        return if (hints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0) Contrast.DARK_TEXT else Color.White
    }

    fun applyLook() {
        val cfg = Store.config.value
        val bg = if (cfg.color.reads) sampled?.background else null
        val text = when (cfg.color) {
            ColorMode.LIGHT -> Color.White
            ColorMode.DARK -> Contrast.DARK_TEXT
            ColorMode.AUTO, ColorMode.OPPOSITE -> sampled?.text ?: bg?.let { Contrast.readableOn(it) } ?: fallbackText()
        }
        val matched = Contrast.resolve(text, bg)
        val live = if (cfg.color == ColorMode.OPPOSITE) Contrast.opposite(matched) else matched
        if (updateLiveLook) BarLook.current.value = live
        look.value = StripLook(live.fg, live.barDark, cfg.textSize, cfg.spacingDp.dp, cfg.pill, live.background)
        Log.i(tag, "look ${cfg.color.name.lowercase()} fg=${hex(live.fg)} bg=${bg?.let { hex(it) } ?: "transparent"} dark=${live.barDark} " +
            "contrast=${"%.1f".format(java.util.Locale.ROOT, Contrast.ratio(live.fg, live.background))}")
    }

    private fun hex(c: Color) = "#%06X".format(java.util.Locale.ROOT, c.toArgb() and 0xFFFFFF)
}
