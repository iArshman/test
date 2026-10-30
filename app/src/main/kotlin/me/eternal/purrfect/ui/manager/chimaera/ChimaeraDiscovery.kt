package me.eternal.purrfect.ui.manager.chimaera

import android.content.SharedPreferences

/**
 * Manages the persistent state of the discovery sequence.
 * This is an internal state manager for advanced discovery phases.
 */
object ChimaeraDiscovery {

    private val windowDurationsMs = listOf(
        5  * 60_000L,   // attempt 1 — 5 min
        10 * 60_000L,   // attempt 2 — 10 min
        20 * 60_000L,   // attempt 3 — 20 min
        40 * 60_000L,   // attempt 4 — 40 min
        60 * 60_000L    // attempt 5+ — 60 min
    )

    // ── Persisted state ───────────────────────────────────────────────────────

    var attemptCount: Int = 0
        private set

    var stageCompletedAt: Long = 0L
        private set

    var unlocked: Boolean = false
        private set

    // ── Derived state ─────────────────────────────────────────────────────────

    val currentWindowMs: Long
        get() = windowDurationsMs[(attemptCount - 1).coerceIn(0, windowDurationsMs.lastIndex)]

    val isWindowActive: Boolean
        get() = !unlocked &&
                stageCompletedAt > 0L &&
                System.currentTimeMillis() - stageCompletedAt < currentWindowMs

    val remainingSeconds: Long
        get() {
            if (!isWindowActive) return 0L
            val elapsed = System.currentTimeMillis() - stageCompletedAt
            return ((currentWindowMs - elapsed) / 1000L).coerceAtLeast(0L)
        }

    val transmissionMessage: String
        get() {
            val totalSeconds = remainingSeconds
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val timer = "%02d:%02d".format(minutes, seconds)
            
            return when (attemptCount) {
                1    -> "CHIMAERA SIGNAL DETECTED\nWINDOW: $timer\nRESPOND IMMEDIATELY\n\n— Kal"
                2    -> "CHIMAERA SIGNAL DETECTED\nWINDOW: $timer\nYOU WERE WARNED\n\n— Kal"
                3    -> "CHIMAERA SIGNAL DETECTED\nWINDOW: $timer\nTHRAWN IS PATIENT. ARE YOU?\n\n— Kal"
                4    -> "CHIMAERA SIGNAL DETECTED\nWINDOW: $timer\nFINAL WARNING\n\n— Kal"
                else -> "CHIMAERA SIGNAL DETECTED\nWINDOW: $timer\nTHE ADMIRAL AWAITS\n\n— Kal"
            }
        }

    val unlockMessage = "ACCESS GRANTED\nWELCOME ABOARD, COMMANDER\n\n— Kal\n  I.S.D. CHIMAERA"

    val welcomeBackMessage = "CHIMAERA UNLOCKED\nWELCOME BACK, COMMANDER\n\n— Kal"

    // ── Actions ───────────────────────────────────────────────────────────────

    fun triggerStage1(prefs: SharedPreferences) {
        if (unlocked) return
        if (!isWindowActive) {
            attemptCount++
            stageCompletedAt = System.currentTimeMillis()
            persist(prefs)
        }
    }

    fun completeStage2(prefs: SharedPreferences) {
        if (!isWindowActive) return
        unlocked = true
        persist(prefs)
    }

    fun load(prefs: SharedPreferences) {
        attemptCount = prefs.getInt(KEY_ATTEMPTS, 0)
        stageCompletedAt = prefs.getLong(KEY_STAGE_AT, 0L)
        unlocked = prefs.getBoolean(KEY_UNLOCKED, false)
    }

    private fun persist(prefs: SharedPreferences) {
        prefs.edit()
            .putInt(KEY_ATTEMPTS, attemptCount)
            .putLong(KEY_STAGE_AT, stageCompletedAt)
            .putBoolean(KEY_UNLOCKED, unlocked)
            .apply()
    }

    private const val KEY_ATTEMPTS  = "chimaera_attempts"
    private const val KEY_STAGE_AT = "chimaera_stage_at"
    private const val KEY_UNLOCKED  = "chimaera_unlocked"
}
