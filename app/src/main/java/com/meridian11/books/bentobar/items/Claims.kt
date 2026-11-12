package com.meridian11.books.bentobar.items

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What another app that shows the bar's items (items/ItemsProvider) asked for: items it shows itself, which the
 * strip then leaves out, and whether it is listing every item right now. Both are kept sampled for it. A claim is a
 * lease: it ends by itself [ClaimRules.LEASE_MS] after it was last renewed, so an app that stops (or is uninstalled)
 * gives the items back to the bar without saying so. Main thread.
 */
object Claims {
    private val main = Handler(Looper.getMainLooper())
    private val current = MutableStateFlow(ClaimRules.Claim())
    val claim: StateFlow<ClaimRules.Claim> get() = current

    private val expire = Runnable { current.value = ClaimRules.Claim() }

    fun set(hide: Set<String>, watch: Boolean, whole: Boolean = false) {
        main.removeCallbacks(expire)
        current.value = ClaimRules.Claim(hide, watch, whole)
        if (!current.value.isEmpty) main.postDelayed(expire, ClaimRules.LEASE_MS)
    }

    /** The ids the strip leaves out now. */
    val hidden: Set<String> get() = current.value.hide

    /** Sharing was switched off: everything goes back to the bar at once. */
    fun clear() {
        main.removeCallbacks(expire)
        current.value = ClaimRules.Claim()
    }
}

/** The rules of a claim, pure. */
object ClaimRules {
    /** How long a claim holds without being renewed. Apps renew theirs about every minute. */
    const val LEASE_MS = 120_000L

    /** [whole]: the app shows the bar's items itself, so the strip draws nothing at all, not even ‹. */
    data class Claim(val hide: Set<String> = emptySet(), val watch: Boolean = false, val whole: Boolean = false) {
        val isEmpty: Boolean get() = hide.isEmpty() && !watch && !whole
    }

    /** Whether the strip draws [id]: not while another app shows it, nor while it shows the whole bar. */
    fun inStrip(id: String, claim: Claim): Boolean = !claim.whole && id !in claim.hide

    /** Whether an item outside Off is sampled for the claim: one the app shows, or any while it lists them all. */
    fun sampled(id: String, claim: Claim): Boolean = claim.watch || id in claim.hide
}
