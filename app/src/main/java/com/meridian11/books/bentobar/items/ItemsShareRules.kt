package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.data.Section

/**
 * What other apps read of the bar's items ([ItemsProvider]), as one table: pure, so its columns and which items go
 * out are pinned by tests. The names are a promise to other apps' code: add columns, never rename or drop one.
 *
 * `items`: what the bar shows now, one row an item, in the bar's order.
 */
object ItemsShareRules {
    /** Raised when the table changes in a way an app must check for; adding a column is not one. */
    const val VERSION = 1

    const val PATH = "items"

    /** Whether a content URI's path names the table. */
    fun isItems(path: List<String>): Boolean = path.size == 1 && path[0] == PATH

    /**
     * `id`: the item's own, stable while it is in the layout. `type`: cpu, battery, timer… `title`: the type's name
     * in the app's language. `text`: what the bar says beside the icon, null for an item that shows an icon alone.
     * `label`: a shorter wording where the text would not fit a list, else null. `description`: the spoken form.
     * `active`: 1 while the item has something to say, else 0. `tone`: normal, accent, warn or alert.
     * `icon_png`: the item's icon as a PNG, white on clear (an app's own icon in its colours), or null.
     * `in_bar`: 1 when the bar itself would show the item now (always shown, or hidden and speaking up), else 0.
     */
    val COLUMNS = arrayOf("id", "type", "title", "text", "label", "description", "active", "tone", "icon_png", "in_bar")

    /** One item as it goes out. */
    data class Shared(
        val id: String,
        val type: String,
        val title: String,
        val text: String?,
        val label: String?,
        val description: String,
        val active: Boolean,
        val tone: Tone,
        val inBar: Boolean = true,
    )

    /**
     * Whether an item goes out: what the bar itself would show. One that is always shown does; a hidden one only
     * while its "show when" rule holds; one that is off never, and neither does a gap between items.
     */
    fun shares(section: Section, whenActive: Boolean, active: Boolean, gap: Boolean): Boolean = inBar(section, whenActive, active, gap)

    /**
     * Whether an item goes out to an app that holds a claim ([ClaimRules.Claim]): what the bar shows, every item
     * outside Off while it lists them all, and the ones it shows itself.
     */
    fun sharesClaimed(id: String, section: Section, whenActive: Boolean, active: Boolean, gap: Boolean, claim: ClaimRules.Claim): Boolean =
        !gap && section != Section.OFF && (inBar(section, whenActive, active, gap) || ClaimRules.sampled(id, claim))

    private fun inBar(section: Section, whenActive: Boolean, active: Boolean, gap: Boolean): Boolean = when {
        gap -> false
        section == Section.SHOWN -> true
        section == Section.HIDDEN -> whenActive && active
        else -> false
    }

    fun row(s: Shared, png: ByteArray?): Array<Any?> = arrayOf(
        s.id,
        s.type,
        s.title,
        s.text?.takeIf { it.isNotEmpty() },
        s.label?.takeIf { it.isNotEmpty() },
        s.description,
        if (s.active) 1 else 0,
        s.tone.name.lowercase(),
        png,
        if (s.inBar) 1 else 0,
    )

    /** Whether what is shared changed, so that apps watching the table are told: the rows' words, not their icons. */
    fun differs(before: List<Shared>?, now: List<Shared>): Boolean = before != now
}
