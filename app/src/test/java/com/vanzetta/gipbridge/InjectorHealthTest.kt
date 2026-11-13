package com.vanzetta.gipbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class InjectorHealthTest {
    private fun decide(
        binderAlive: Boolean = true,
        permission: Boolean = true,
        connected: Boolean = false,
        unhealthyMs: Long = 0,
        sinceRestartMs: Long = Long.MAX_VALUE,
    ) = InjectorHealth.decide(binderAlive, permission, connected, unhealthyMs, sinceRestartMs)

    @Test fun connectedInjectorNeedsNothing() =
        assertEquals(Heal.NONE, decide(connected = true, unhealthyMs = 999_999))

    @Test fun freshFailureIsGivenTimeToConnect() =
        assertEquals(Heal.NONE, decide(unhealthyMs = InjectorHealth.GRACE_MS - 1))

    @Test fun liveShizukuWithoutInjectorIsRebound() =
        assertEquals(Heal.REBIND, decide(unhealthyMs = InjectorHealth.GRACE_MS))

    @Test fun missingBinderRestartsShizuku() =
        assertEquals(Heal.RESTART_SHIZUKU, decide(binderAlive = false, unhealthyMs = InjectorHealth.GRACE_MS))

    @Test fun rebindThatStaysUselessEscalatesToRestart() =
        assertEquals(Heal.RESTART_SHIZUKU, decide(unhealthyMs = InjectorHealth.RESTART_AFTER_MS))

    @Test fun restartsAreRateLimited() =
        assertEquals(
            Heal.NONE,
            decide(binderAlive = false, unhealthyMs = 999_999, sinceRestartMs = InjectorHealth.RESTART_COOLDOWN_MS - 1),
        )

    @Test fun ungrantedPermissionIsLeftToTheUser() =
        assertEquals(Heal.NONE, decide(permission = false, unhealthyMs = 999_999))

    @Test fun afterWakeADroppedInjectorIsReboundWithoutWaitingForTheGracePeriod() =
        assertEquals(Heal.REBIND, InjectorHealth.decideAfterWake(binderAlive = true, permissionGranted = true, injectorConnected = false))

    @Test fun afterWakeAConnectedInjectorIsLeftAlone() =
        assertEquals(Heal.NONE, InjectorHealth.decideAfterWake(binderAlive = true, permissionGranted = true, injectorConnected = true))

    @Test fun afterWakeMissingBinderIsLeftToTheNormalEscalation() =
        assertEquals(Heal.NONE, InjectorHealth.decideAfterWake(binderAlive = false, permissionGranted = false, injectorConnected = false))

    @Test fun afterWakeUngrantedPermissionIsLeftToTheUser() =
        assertEquals(Heal.NONE, InjectorHealth.decideAfterWake(binderAlive = true, permissionGranted = false, injectorConnected = false))
}
