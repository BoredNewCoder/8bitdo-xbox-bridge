package com.vanzetta.gipbridge

/** Bounded back-off for reopening the USB session after a temporary failure. */
object OpenRetry {
    private val DELAYS_MS = longArrayOf(500, 1_000, 2_000, 4_000, 8_000)
    val MAX_RETRIES: Int = DELAYS_MS.size

    /** Pause before retry number attempt + 1, or null once the budget is spent. */
    fun delayMs(attempt: Int): Long? = DELAYS_MS.getOrNull(attempt)
}
