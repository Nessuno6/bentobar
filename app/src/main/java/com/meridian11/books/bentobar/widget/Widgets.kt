package com.meridian11.books.bentobar.widget

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.RemoteViews
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.items.Cases
import com.meridian11.books.bentobar.items.DevicesItem
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.Link
import com.meridian11.books.bentobar.widget.WidgetRules.Cell
import com.meridian11.books.bentobar.widget.WidgetRules.Grid
import com.meridian11.books.bentobar.widget.WidgetRules.Kind
import java.util.concurrent.Executors

/**
 * Device batteries on the home screen: this device, what Device batteries finds here, what the linked BentoBar tells
 * of (items/Link: the other phone or tablet and its devices) and the earbuds' cases as last seen. Sizes: 4 x 2, one to
 * four devices in a row (a column when resized tall), and a custom grid. Each widget's settings pin devices to cells;
 * the others fill in the order set there.
 */
abstract class BaseWidget : AppWidgetProvider() {
    /** The grid for widget [id] at its size ([opts]: the launcher's size options). */
    abstract fun grid(ctx: Context, id: Int, opts: Bundle): Grid

    /** How many cells its settings offer. */
    open fun cells(ctx: Context, id: Int): Int = grid(ctx, id, Bundle()).cells

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetJob.schedule(ctx)
        val p = goAsync()
        WidgetUpdater.updateAsync(ctx, ids) { p.finish() }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        val p = goAsync()
        WidgetUpdater.updateAsync(ctx, intArrayOf(id)) { p.finish() }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) = ids.forEach { WidgetStore.delete(ctx, it) }

    override fun onDisabled(ctx: Context) {
        if (WidgetUpdater.allIds(ctx).isEmpty()) WidgetJob.cancel(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == WidgetUpdater.REFRESH) {
            val p = goAsync()
            WidgetUpdater.updateAsync(ctx) { p.finish() }
        }
    }
}

class MainWidget : BaseWidget() {
    override fun grid(ctx: Context, id: Int, opts: Bundle) = WidgetRules.mainGrid(opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT))
    override fun cells(ctx: Context, id: Int) = 8
}

abstract class StripWidget(private val n: Int) : BaseWidget() {
    override fun grid(ctx: Context, id: Int, opts: Bundle) = WidgetRules.stripGrid(n,
        opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
    override fun cells(ctx: Context, id: Int) = n
}

class Widget1 : StripWidget(1)
class Widget2 : StripWidget(2)
class Widget3 : StripWidget(3)
class Widget4 : StripWidget(4)

/**
 * The same, on One UI's lock screen: a widget there can't also be on the home screen (the system gives it the lock
 * screen's category alone), so these are widgets of their own, in the lock screen's sizes.
 */
class LockWidget1 : StripWidget(1)
class LockWidget2 : StripWidget(2)

class CustomWidget : BaseWidget() {
    override fun grid(ctx: Context, id: Int, opts: Bundle) = WidgetStore.grid(ctx, id)
}

/** Refreshes the widgets every 15 minutes, the least Android allows; set again whenever a widget updates. */
class WidgetJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        WidgetUpdater.updateAsync(this) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters?) = false

    companion object {
        private const val ID = 0x5769 // "Wi"

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(ID) != null) return
            // Not persisted (that would need the boot permission): after a restart the widgets' own update sets it again.
            js.schedule(JobInfo.Builder(ID, ComponentName(ctx, WidgetJob::class.java)).setPeriodic(15 * 60_000L).build())
        }

        fun cancel(ctx: Context) = ctx.getSystemService(JobScheduler::class.java)?.cancel(ID)
    }
}

object WidgetUpdater {
    const val REFRESH = "com.meridian11.books.bentobar.widget.REFRESH"

    val PROVIDERS: List<Class<out BaseWidget>> = listOf(
        MainWidget::class.java, Widget1::class.java, Widget2::class.java, Widget3::class.java, Widget4::class.java, CustomWidget::class.java,
        LockWidget1::class.java, LockWidget2::class.java,
    )

    private val ROWS = intArrayOf(R.id.widget_row0, R.id.widget_row1, R.id.widget_row2, R.id.widget_row3)
    private val CELLS = intArrayOf(
        R.id.widget_cell0, R.id.widget_cell1, R.id.widget_cell2, R.id.widget_cell3,
        R.id.widget_cell4, R.id.widget_cell5, R.id.widget_cell6, R.id.widget_cell7,
        R.id.widget_cell8, R.id.widget_cell9, R.id.widget_cell10, R.id.widget_cell11,
        R.id.widget_cell12, R.id.widget_cell13, R.id.widget_cell14, R.id.widget_cell15,
    )

    private val FG = intArrayOf(
        R.id.widget_fg0, R.id.widget_fg1, R.id.widget_fg2, R.id.widget_fg3,
        R.id.widget_fg4, R.id.widget_fg5, R.id.widget_fg6, R.id.widget_fg7,
        R.id.widget_fg8, R.id.widget_fg9, R.id.widget_fg10, R.id.widget_fg11,
        R.id.widget_fg12, R.id.widget_fg13, R.id.widget_fg14, R.id.widget_fg15,
    )
    private val ARC = intArrayOf(
        R.id.widget_arc0, R.id.widget_arc1, R.id.widget_arc2, R.id.widget_arc3,
        R.id.widget_arc4, R.id.widget_arc5, R.id.widget_arc6, R.id.widget_arc7,
        R.id.widget_arc8, R.id.widget_arc9, R.id.widget_arc10, R.id.widget_arc11,
        R.id.widget_arc12, R.id.widget_arc13, R.id.widget_arc14, R.id.widget_arc15,
    )

    /** Reading the devices can wait for Bluetooth or root: never on the main thread. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "BentoBar widget") }
    private val main = Handler(Looper.getMainLooper())
    private val soon = Runnable { updateAsync(Env.app) }

    fun allIds(ctx: Context): IntArray {
        val mgr = AppWidgetManager.getInstance(ctx)
        return PROVIDERS.flatMap { mgr.getAppWidgetIds(ComponentName(ctx, it)).toList() }.toIntArray()
    }

    fun providerOf(ctx: Context, id: Int): BaseWidget? {
        val cls = AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id)?.provider?.className ?: return null
        return PROVIDERS.firstOrNull { it.name == cls }?.getDeclaredConstructor()?.newInstance()
    }

    /** Something the widgets show changed (a reading, the link): update them in a moment, once for a burst. */
    fun changed() {
        main.removeCallbacks(soon)
        main.postDelayed(soon, 1_500L)
    }

    fun updateAsync(ctx: Context, ids: IntArray? = null, done: () -> Unit = {}) {
        val app = ctx.applicationContext
        worker.execute {
            try {
                Env.init(app)
                update(app, ids ?: allIds(app))
            } catch (_: Throwable) {
                // A widget that can't be drawn keeps what it showed.
            } finally {
                done()
            }
        }
    }

    @Volatile private var linked: List<com.meridian11.books.bentobar.items.DevicesRules.Device> = emptyList()
    @Volatile private var linkedAt = 0L

    /**
     * The linked side's devices. The link often drops and comes back within seconds: what it last said is kept for
     * [LINK_GRACE_MS], so the widget doesn't lose the other device in between.
     */
    private fun linked(own: List<com.meridian11.books.bentobar.items.DevicesRules.Device>): List<com.meridian11.books.bentobar.items.DevicesRules.Device> {
        val now = android.os.SystemClock.elapsedRealtime()
        val fresh = Link.devices(own)
        if (fresh.isNotEmpty()) {
            linked = fresh
            linkedAt = now
            return fresh
        }
        return if (now - linkedAt < LINK_GRACE_MS) linked else emptyList()
    }

    private const val LINK_GRACE_MS = 2 * 60_000L

    /** Every device the widgets can show, in the user's order. Background thread. */
    fun cells(ctx: Context): List<Cell> {
        val own = DevicesItem.forOtherApps()
        val cases = Cases.all().map { c ->
            Cell("case:${c.name}", ctx.getString(R.string.devices_case_of, c.name), Kind.CASE, c.level, Cases.charging(c.name), seenAt = c.at)
        }
        val all = WidgetRules.ordered(WidgetRules.cells(self(ctx), own + linked(own), cases), WidgetStore.priority(ctx))
        WidgetStore.remember(ctx, all)
        return all
    }

    private fun self(ctx: Context): Cell? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val tablet = ctx.resources.configuration.smallestScreenWidthDp >= 600
        val name = Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
        return Cell(WidgetRules.SELF, name, if (tablet) Kind.TABLET else Kind.PHONE,
            level.takeIf { it >= 0 }?.let { it * 100 / scale }, i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
    }

    private fun update(ctx: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        val mgr = AppWidgetManager.getInstance(ctx)
        val all = cells(ctx)
        val hidden = WidgetStore.hidden(ctx)
        val visible = all.filter { it.key !in hidden }
        val known = WidgetStore.known(ctx)
        val text = WidgetStore.text(ctx)
        // Followed by the launcher itself, light or dark, without a new drawing; else one colour, as chosen.
        val black = ColorStateList.valueOf(Color.BLACK)
        val white = ColorStateList.valueOf(Color.WHITE)
        val fixed = if (WidgetArc.darkText(ctx)) black else white
        for (id in ids) {
            val provider = providerOf(ctx, id) ?: continue
            val grid = provider.grid(ctx, id, mgr.getAppWidgetOptions(id))
            val cells = WidgetRules.assign(WidgetStore.slots(ctx, id), grid.cells, visible, all, known)
            val rv = RemoteViews(ctx.packageName, R.layout.widget_grid)
            for (r in ROWS.indices) rv.setViewVisibility(ROWS[r], if (r < grid.rows) View.VISIBLE else View.GONE)
            for (r in 0 until grid.rows) for (c in 0 until WidgetRules.MAX) {
                val view = CELLS[r * WidgetRules.MAX + c]
                if (c >= grid.cols) {
                    rv.setViewVisibility(view, View.GONE)
                    continue
                }
                val cell = cells[r * grid.cols + c]
                val i = r * WidgetRules.MAX + c
                rv.setViewVisibility(view, View.VISIBLE)
                rv.setImageViewBitmap(FG[i], WidgetArc.foreground(ctx, cell))
                rv.setImageViewBitmap(ARC[i], WidgetArc.colour(cell))
                if (text == WidgetStore.Text.SYSTEM) rv.setColorStateList(FG[i], "setImageTintList", black, white)
                else rv.setColorStateList(FG[i], "setImageTintList", fixed)
                rv.setContentDescription(view, cell?.let { describe(ctx, it) } ?: "")
            }
            rv.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getBroadcast(ctx, id,
                Intent(ctx, provider.javaClass).setAction(REFRESH), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            mgr.updateAppWidget(id, rv)
        }
    }

    private fun describe(ctx: Context, c: Cell): String = when {
        c.percent == null -> ctx.getString(R.string.devices_desc_unknown, c.name)
        c.charging -> ctx.resources.getQuantityString(R.plurals.devices_desc_charging, c.percent, c.name, c.percent)
        else -> ctx.resources.getQuantityString(R.plurals.devices_desc, c.percent, c.name, c.percent)
    }
}
