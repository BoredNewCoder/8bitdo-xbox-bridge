package com.vanzetta.gipbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHealthTest {
    @Test fun freshSessionIsAlive() =
        assertFalse(SessionHealth.isDead(silentMs = 0))

    @Test fun beyondOneStatusPeriodIsStillAlive() =
        assertFalse(SessionHealth.isDead(silentMs = SessionHealth.SILENT_MS - 1))

    @Test fun silenceAtLimitMeansDead() =
        assertTrue(SessionHealth.isDead(silentMs = SessionHealth.SILENT_MS))

    @Test fun hoursOfSleepMeansDead() =
        assertTrue(SessionHealth.isDead(silentMs = 13L * 60 * 60 * 1000))

    @Test fun clockGoingBackwardsIsNotDeath() =
        assertFalse(SessionHealth.isDead(silentMs = -1))

    @Test fun silenceLimitSpansSeveralControllerStatusPackets() =
        assertTrue(SessionHealth.SILENT_MS >= 3 * SessionHealth.CONTROLLER_STATUS_PERIOD_MS)
}
