package com.meridian11.books.bentobar.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Whether other apps may read Device batteries (items/DevicesProvider): off until the user switches it on in the
 * item's settings. Each app still needs Android's permission for it as well, which the user grants app by app.
 *
 * It belongs to this install, as [Online]'s switches do: one small file in Android's no-backup directory, so a
 * pasted layout, a restored backup or a device transfer never turns it on.
 */
object Sharing {
    private const val FILE = "share-devices"
    private var file: File? = null
    private val current = MutableStateFlow(false)
    val on: StateFlow<Boolean> get() = current

    /** [noBackupDir]: `Context.getNoBackupFilesDir()`. A file that can't be read counts as off. */
    @Synchronized
    fun init(noBackupDir: File) {
        file = File(noBackupDir, FILE)
        current.value = runCatching { file?.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim() == ON }.getOrDefault(false)
        itemsFile = File(noBackupDir, ITEMS_FILE)
        currentItems.value = runCatching { itemsFile?.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim() == ON }.getOrDefault(false)
    }

    @Synchronized
    fun set(on: Boolean) {
        runCatching { file?.writeText(if (on) ON else OFF, Charsets.UTF_8) }
        current.value = on
    }

    // ---- The bar's items (items/ItemsProvider): a switch of its own, kept the same way. ----
    private const val ITEMS_FILE = "share-items"
    private var itemsFile: File? = null
    private val currentItems = MutableStateFlow(false)
    val items: StateFlow<Boolean> get() = currentItems

    @Synchronized
    fun setItems(on: Boolean) {
        runCatching { itemsFile?.writeText(if (on) ON else OFF, Charsets.UTF_8) }
        currentItems.value = on
        if (!on) com.meridian11.books.bentobar.items.Claims.clear()
    }

    private const val ON = "on"
    private const val OFF = "off"
}
