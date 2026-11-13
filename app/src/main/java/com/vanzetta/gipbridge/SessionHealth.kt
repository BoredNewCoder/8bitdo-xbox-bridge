package com.vanzetta.gipbridge

/** Pure decision: is a controller session dead because the pad stopped talking. */
object SessionHealth {
    // The pad sends a STATUS packet about every 20 s while the session is healthy.
    const val CONTROLLER_STATUS_PERIOD_MS = 20_000L
    const val SILENT_MS = 60_000L

    fun isDead(silentMs: Long): Boolean = silentMs >= SILENT_MS
}
