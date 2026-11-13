package com.vanzetta.gipbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeRecoveryTest {
    @Test fun firstWakeSignalIsAcceptedAndReturnsItsTime() =
        assertEquals(1_000L, WakeRecovery().onWakeSignal(now = 1_000))

    @Test fun secondSignalRightAfterIsMerged() {
        val w = WakeRecovery()
        w.onWakeSignal(now = 1_000)
        assertNull(w.onWakeSignal(now = 1_200))
    }

    @Test fun signalJustInsideTheMergeWindowIsMerged() {
        val w = WakeRecovery()
        w.onWakeSignal(now = 1_000)
        assertNull(w.onWakeSignal(now = 1_000 + WakeRecovery.MERGE_MS - 1))
    }

    @Test fun signalAfterTheMergeWindowStartsANewCheck() {
        val w = WakeRecovery()
        w.onWakeSignal(now = 1_000)
        assertEquals(1_000 + WakeRecovery.MERGE_MS, w.onWakeSignal(now = 1_000 + WakeRecovery.MERGE_MS))
    }

    @Test fun mergedSignalDoesNotExtendTheWindow() {
        val w = WakeRecovery()
        w.onWakeSignal(now = 1_000)
        w.onWakeSignal(now = 4_000)
        assertEquals(1_000 + WakeRecovery.MERGE_MS, w.onWakeSignal(now = 1_000 + WakeRecovery.MERGE_MS))
    }

    @Test fun clockGoingBackwardsStartsANewCheck() {
        val w = WakeRecovery()
        w.onWakeSignal(now = 10_000)
        assertEquals(5_000L, w.onWakeSignal(now = 5_000))
    }

    @Test fun packetBeforeWakeMeansRestart() =
        assertTrue(WakeRecovery.needsRestart(lastPacketAt = 999, wakeAt = 1_000))

    @Test fun packetAtWakeTimeMeansHealthy() =
        assertFalse(WakeRecovery.needsRestart(lastPacketAt = 1_000, wakeAt = 1_000))

    @Test fun packetAfterWakeMeansHealthy() =
        assertFalse(WakeRecovery.needsRestart(lastPacketAt = 1_500, wakeAt = 1_000))

    @Test fun packetWindowIsShorterThanTheMergeWindow() =
        assertTrue(WakeRecovery.PACKET_WINDOW_MS < WakeRecovery.MERGE_MS)

    @Test fun packetWindowIsMuchShorterThanTheSilenceWatchdog() =
        assertTrue(WakeRecovery.PACKET_WINDOW_MS * 5 <= SessionHealth.SILENT_MS)
}
