package com.meridian11.books.bentobar.items

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.meridian11.books.bentobar.data.Sharing
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.items.ItemsShareRules.Shared
import java.io.ByteArrayOutputStream
import java.util.WeakHashMap

/**
 * The bar's items for other apps: `content://com.meridian11.books.bentobar.items/items`, read only, and one request,
 * to open an item's popup ([call]). The table:
 * ([ItemsShareRules] has the columns). Android lets an app ask only with [PERMISSION], which the user grants it (the
 * manifest says so), and BentoBar answers only while the user switched this sharing on ([Sharing.items]); off, the
 * table is empty and its extras say `enabled` false. Nothing is written, and no caller or text is logged.
 *
 * The rows are what the bar shows at that moment: nothing is sampled for a query, so the table is empty while the bar
 * is hidden or the screen is off. Apps that watch it are told when its rows change, at most once a tick.
 */
class ItemsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val context = context ?: return null
        if (!ItemsShareRules.isItems(uri.pathSegments)) throw IllegalArgumentException("Not a table: ${uri.path}")
        // What follows reads BentoBar's own state as BentoBar, not as the caller.
        val token = Binder.clearCallingIdentity()
        try {
            Env.init(context)
            val on = Sharing.items.value
            val cursor = MatrixCursor(ItemsShareRules.COLUMNS)
            cursor.extras = Bundle().apply {
                putBoolean(EXTRA_ENABLED, on)
                putInt(EXTRA_VERSION, ItemsShareRules.VERSION)
            }
            cursor.setNotificationUri(context.contentResolver, uri)
            if (!on) return cursor
            for ((shared, state) in shown(Ticker.states.value)) cursor.addRow(ItemsShareRules.row(shared, png(shared.type, state)))
            return cursor
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    /**
     * [METHOD_OPEN]: what a click on the item [arg] does in the bar, its popup under the rectangle in [extras]
     * (`left`, `top`, `right`, `bottom`, screen pixels). The same two locks as the table: the permission, asked for
     * here since Android checks it for the table only, and the switch. The answer says `opened`.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        if (method != METHOD_OPEN && method != METHOD_CLAIM && method != METHOD_CLOSE) return null
        context.enforceCallingPermission(PERMISSION, "BentoBar's items")
        if (method == METHOD_CLAIM) return claim(context, extras)
        if (method == METHOD_CLOSE) {
            val token = Binder.clearCallingIdentity()
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    (Env.service as? com.meridian11.books.bentobar.bar.BarService)?.controller()?.closeShared()
                }
                return Bundle()
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }
        val id = arg ?: return null
        val at = android.graphics.Rect(
            extras?.getInt("left") ?: 0, extras?.getInt("top") ?: 0, extras?.getInt("right") ?: 0, extras?.getInt("bottom") ?: 0)
        val token = Binder.clearCallingIdentity()
        try {
            Env.init(context)
            val on = Sharing.items.value
            // The bar lives on the main thread; the caller need not wait for the popup.
            if (on) android.os.Handler(android.os.Looper.getMainLooper()).post {
                (Env.service as? com.meridian11.books.bentobar.bar.BarService)?.controller()?.openAt(id, at, hover = extras?.getBoolean(EXTRA_HOVER) == true)
            }
            return Bundle().apply { putBoolean(EXTRA_ENABLED, on); putBoolean(EXTRA_OPENED, on && Env.service != null) }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    /**
     * [METHOD_CLAIM]: the caller shows items itself. `hide` (a string list of ids): the strip leaves these out, and
     * they stay sampled; `watch` (boolean): it lists every item now, so every item outside Off is sampled and shared;
     * `whole` (boolean): it shows the bar's items itself, so the strip draws nothing (the bar keeps sampling).
     * A lease ([ClaimRules.LEASE_MS]): renewed by asking again, given up by asking with nothing. Only while sharing is
     * on; the answer says `enabled`.
     */
    private fun claim(context: android.content.Context, extras: Bundle?): Bundle {
        val token = Binder.clearCallingIdentity()
        try {
            Env.init(context)
            val on = Sharing.items.value
            val hide = extras?.getStringArrayList(EXTRA_HIDE).orEmpty().toSet()
            val watch = extras?.getBoolean(EXTRA_WATCH) ?: false
            val whole = extras?.getBoolean(EXTRA_WHOLE) ?: false
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (on) Claims.set(hide, watch, whole) else Claims.clear()
                Ticker.refresh()
            }
            return Bundle().apply { putBoolean(EXTRA_ENABLED, on) }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun getType(uri: Uri): String? =
        if (ItemsShareRules.isItems(uri.pathSegments)) "vnd.android.cursor.dir/vnd.$AUTHORITY.item" else null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")

    companion object {
        const val AUTHORITY = "com.meridian11.books.bentobar.items"
        const val PERMISSION = "com.meridian11.books.bentobar.permission.READ_BAR_ITEMS"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_VERSION = "version"
        const val METHOD_OPEN = "open"
        const val METHOD_CLAIM = "claim"
        /** The caller moved on: the popup [METHOD_OPEN] last opened for it closes, if it's still showing. */
        const val METHOD_CLOSE = "close"
        const val EXTRA_HIDE = "hide"
        const val EXTRA_WATCH = "watch"
        /** The caller shows the bar's items itself: the strip draws nothing, ‹ included. */
        const val EXTRA_WHOLE = "whole"
        const val EXTRA_OPENED = "opened"
        /** [METHOD_OPEN] for a pointer resting on the item: its popup only, never its click, and an open one stays. */
        const val EXTRA_HOVER = "hover"
        val BASE: Uri = Uri.parse("content://$AUTHORITY")
        private const val ICON_PX = 96

        @Volatile private var last: List<Shared>? = null
        private val glyphs = HashMap<String, ByteArray>()
        private val images = WeakHashMap<Bitmap, ByteArray>()

        /** The items that go out, in the bar's order, each with the state it was made from. */
        private fun shown(states: Map<String, ItemState>): List<Pair<Shared, ItemState>> =
            Store.config.value.items.mapNotNull { item ->
                val state = states[item.id] ?: return@mapNotNull null
                val type = Items.of(item.type) ?: return@mapNotNull null
                val gap = state.gapDp > 0 || state.divider
                if (!ItemsShareRules.sharesClaimed(item.id, item.section, item.whenActive, state.active, gap, Claims.claim.value)) return@mapNotNull null
                Shared(item.id, item.type, type.title, state.text, state.label, state.desc, state.active, state.tone,
                    inBar = ItemsShareRules.shares(item.section, item.whenActive, state.active, gap)) to state
            }

        /** The item's icon as a PNG: an app's own where it has one, else its symbol in white. */
        private fun png(type: String, state: ItemState): ByteArray? = runCatching {
            state.image?.let { image ->
                return@runCatching synchronized(images) { images.getOrPut(image) { encode(image) } }
            }
            val sym = state.icon ?: Items.of(type)?.icon ?: return@runCatching null
            // One UI's icon where this build has one and the bar uses them (Look), as the bar draws it.
            val oneUi = if (Store.config.value.oneUiIcons) com.meridian11.books.bentobar.util.OneUiIcons.drawable(Env.app, sym) else 0
            if (oneUi != 0) return@runCatching synchronized(glyphs) { glyphs.getOrPut("oneui:$sym") { encode(drawn(oneUi)) } }
            synchronized(glyphs) { glyphs.getOrPut(sym) { encode(Glyphs.bitmap(sym, ICON_PX)) } }
        }.getOrNull()

        /** A vector drawable in white, [ICON_PX] square. */
        private fun drawn(id: Int): Bitmap {
            val b = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
            Env.app.getDrawable(id)?.mutate()?.apply {
                setTint(android.graphics.Color.WHITE)
                setBounds(0, 0, ICON_PX, ICON_PX)
                draw(android.graphics.Canvas(b))
            }
            return b
        }

        private fun encode(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

        /** The bar has new states: apps watching the table hear of it when its rows changed and sharing is on. */
        fun changed(states: Map<String, ItemState>) {
            if (!Sharing.items.value) { last = null; return }
            val now = shown(states).map { it.first }
            val before = last
            last = now
            if (!ItemsShareRules.differs(before, now)) return
            runCatching { Env.app.contentResolver.notifyChange(BASE, null) }
        }
    }
}
