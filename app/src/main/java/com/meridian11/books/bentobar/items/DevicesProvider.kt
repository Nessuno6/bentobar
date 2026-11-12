package com.meridian11.books.bentobar.items

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.meridian11.books.bentobar.data.Sharing
import com.meridian11.books.bentobar.items.DevicesShareRules.Table

/**
 * Device batteries for other apps: `content://com.meridian11.books.bentobar.devices/devices` and `…/cases`, read
 * only ([DevicesShareRules] has the columns). Android lets an app ask only with [PERMISSION], which the user grants it
 * (the manifest says so), and BentoBar answers only while the user switched sharing on ([Sharing]); off, every table
 * is empty and its extras say `enabled` false. Nothing is written, and no caller or name is logged.
 *
 * A query reads the devices anew when what was read is over [DevicesShareRules.FRESH_MS] old, as Device batteries
 * reads them for the bar. Apps that watch a table are told when its rows change while BentoBar reads the devices.
 */
class DevicesProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val context = context ?: return null
        val table = DevicesShareRules.table(uri.pathSegments) ?: throw IllegalArgumentException("Not a table: ${uri.path}")
        // What follows reads BentoBar's own state and asks Android as BentoBar, not as the caller.
        val token = Binder.clearCallingIdentity()
        try {
            Env.init(context)
            val on = Sharing.on.value
            val cursor = MatrixCursor(DevicesShareRules.columns(table))
            cursor.extras = Bundle().apply {
                putBoolean(EXTRA_ENABLED, on)
                putInt(EXTRA_VERSION, DevicesShareRules.VERSION)
            }
            cursor.setNotificationUri(context.contentResolver, uri)
            if (!on) return cursor
            when (table) {
                Table.DEVICES -> DevicesItem.forOtherApps().forEach { cursor.addRow(DevicesShareRules.row(it)) }
                Table.CASES -> Cases.all().forEach { cursor.addRow(DevicesShareRules.row(it)) }
            }
            return cursor
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun getType(uri: Uri): String? = when (DevicesShareRules.table(uri.pathSegments)) {
        Table.DEVICES -> "vnd.android.cursor.dir/vnd.$AUTHORITY.device"
        Table.CASES -> "vnd.android.cursor.dir/vnd.$AUTHORITY.case"
        null -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")

    companion object {
        const val AUTHORITY = "com.meridian11.books.bentobar.devices"
        const val PERMISSION = "com.meridian11.books.bentobar.permission.READ_DEVICE_BATTERIES"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_VERSION = "version"
        val BASE: Uri = Uri.parse("content://$AUTHORITY")

        @Volatile private var last: List<DevicesRules.Device>? = null

        /**
         * A new reading of the devices: the home screen widgets hear of it when the rows changed, and apps watching the
         * tables too while sharing is on.
         */
        fun read(devices: List<DevicesRules.Device>) {
            val before = last
            last = devices
            if (!DevicesShareRules.differs(before, devices)) return
            com.meridian11.books.bentobar.widget.WidgetUpdater.changed()
            if (!Sharing.on.value) return
            // The base URI: Android tells observers of each table under it too.
            runCatching { Env.app.contentResolver.notifyChange(BASE, null) }
        }
    }
}
