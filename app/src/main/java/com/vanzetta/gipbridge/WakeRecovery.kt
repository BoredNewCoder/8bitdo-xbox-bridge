package com.vanzetta.gipbridge

/**
 * After the TV wakes, several signals arrive for one wake-up (screen on, user present).
 * They are merged into one check: if the pad sent nothing since the wake-up, reopen its session.
 */
class WakeRecovery {
    private var acceptedAt: Long? = null

    /** Returns the wake time to check against, or null when this signal belongs to a wake-up already handled. */
    fun onWakeSignal(now: Long): Long? {
        val last = acceptedAt
        if (last != null && now - last in 0 until MERGE_MS) return null
        acceptedAt = now
        return now
    }

    companion object {
        // How long a session gets to deliver a packet after the wake-up before it is reopened.
        const val PACKET_WINDOW_MS = 2_000L
        const val MERGE_MS = 5_000L

        fun needsRestart(lastPacketAt: Long, wakeAt: Long): Boolean = lastPacketAt < wakeAt
    }
}
