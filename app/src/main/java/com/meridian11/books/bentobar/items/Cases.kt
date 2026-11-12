package com.meridian11.books.bentobar.items

import android.content.Context

/**
 * The earbuds' charging cases as last read ([DevicesRules.Case]): Samsung knows a case's level only while an earbud is in
 * it or just out of it, so the last level read is kept, in BentoBar's own preferences, and shown until the next reading.
 * Kept by the earbuds' name, never their address.
 */
object Cases {
    private val prefs by lazy { Env.app.getSharedPreferences("devices", Context.MODE_PRIVATE) }
    @Volatile private var cached: List<DevicesRules.Case>? = null

    fun all(): List<DevicesRules.Case> = cached ?: DevicesRules.decodeCases(prefs.getString(KEY, null)).also { cached = it }

    /** A reading of [name]'s case: kept, unless it says what was kept already a moment ago. Any thread. */
    /** When each case was last seen charging (wall clock), in memory only: it says so only while a bud is in it. */
    private val chargingAt = HashMap<String, Long>()

    /** Whether [name]'s case was charging when last read, a moment ago. */
    @Synchronized fun charging(name: String): Boolean =
        chargingAt[name]?.let { System.currentTimeMillis() - it < DevicesRules.CASE_FRESH_MS } == true

    @Synchronized fun saw(name: String, level: Int, charging: Boolean = false) {
        if (name.isBlank()) return
        val now = System.currentTimeMillis()
        if (charging) chargingAt[name] = now else chargingAt.remove(name)
        val was = all().firstOrNull { it.name == name }
        if (was != null && was.level == level && now - was.at < DevicesRules.CASE_FRESH_MS / 2) return
        val next = DevicesRules.remember(all(), name, level, now)
        cached = next
        prefs.edit().putString(KEY, DevicesRules.encodeCases(next)).apply()
    }

    private const val KEY = "cases"
}
