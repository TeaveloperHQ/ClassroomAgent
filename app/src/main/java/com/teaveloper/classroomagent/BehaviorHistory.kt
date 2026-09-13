package com.teaveloper.classroomagent

import android.content.Context
import android.content.SharedPreferences
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Personal, per-agent history of "which apps did I positively use at this
 * day-of-week + hour slot in the past". Used as a fast-path that skips
 * waiting for classroom-wide consensus for apps the student regularly uses
 * at this time.
 *
 * A "positive use" = the app was in allowedPackages at the moment of use AND
 * the class session ended normally with the app having been used. Recorded
 * once per session per app (not once per touch event) so counters reflect
 * distinct successful sessions, not activity volume.
 *
 * Key schema: "<pkg>|<DAY_OF_WEEK>|<hour>" → Int count.
 *   Example: "com.microsoft.office.onenote|MONDAY|9" = 4
 *   means the student has had 4 positive Monday-9AM sessions with OneNote.
 *
 * A pkg is considered "regular" for the current slot once its count >= 3,
 * which is roughly "3 weeks of the same pattern" if this class meets weekly.
 */
object BehaviorHistory {

    private const val PREFS = "behavior_history"
    private const val REGULAR_THRESHOLD = 3

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun slotKey(pkg: String, day: DayOfWeek, hour: Int) =
        "$pkg|${day.name}|$hour"

    /** One increment per (pkg, day, hour) call. Idempotency per session is the caller's job. */
    fun recordPositive(pkg: String) {
        if (pkg.isBlank() || !this::prefs.isInitialized) return
        val now = LocalDateTime.now()
        val key = slotKey(pkg, now.dayOfWeek, now.hour)
        val n = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, n).apply()
        android.util.Log.d("BehaviorHistory", "positive $key -> $n")
    }

    fun isRegular(pkg: String): Boolean {
        if (!this::prefs.isInitialized || pkg.isBlank()) return false
        val now = LocalDateTime.now()
        val key = slotKey(pkg, now.dayOfWeek, now.hour)
        return prefs.getInt(key, 0) >= REGULAR_THRESHOLD
    }

    /**
     * All pkgs whose (day-of-week, hour, pkg) counter has reached the regular
     * threshold for the current time slot. Used at class START to preload
     * these into allowedPackages so DIAG shows them as fast-pathed rather than
     * discovering them only when opened.
     */
    fun regularForNow(): Set<String> {
        if (!this::prefs.isInitialized) return emptySet()
        val now = LocalDateTime.now()
        val suffix = "|${now.dayOfWeek.name}|${now.hour}"
        return prefs.all
            .asSequence()
            .filter { (k, v) -> k.endsWith(suffix) && (v as? Int ?: 0) >= REGULAR_THRESHOLD }
            .map { it.key.substringBefore("|") }
            .toSet()
    }
}
