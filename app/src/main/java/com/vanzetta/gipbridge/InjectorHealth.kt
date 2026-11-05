package com.vanzetta.gipbridge

enum class Heal { NONE, REBIND, RESTART_SHIZUKU }

/** Pure decision: what to do about an injector that is not connected. */
object InjectorHealth {
    const val GRACE_MS = 15_000L
    const val RESTART_AFTER_MS = 45_000L
    const val RESTART_COOLDOWN_MS = 120_000L

    fun decide(
        binderAlive: Boolean,
        permissionGranted: Boolean,
        injectorConnected: Boolean,
        unhealthyMs: Long,
        sinceRestartMs: Long,
    ): Heal = when {
        injectorConnected || unhealthyMs < GRACE_MS -> Heal.NONE
        binderAlive && !permissionGranted -> Heal.NONE
        !binderAlive || unhealthyMs >= RESTART_AFTER_MS ->
            if (sinceRestartMs >= RESTART_COOLDOWN_MS) Heal.RESTART_SHIZUKU else Heal.NONE
        else -> Heal.REBIND
    }
}
