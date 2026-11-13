package com.vanzetta.gipbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRetryTest {
    @Test fun firstRetryWaitsAShortWhile() {
        val d = OpenRetry.delayMs(attempt = 0)
        assertNotNull(d)
        assertTrue(d!! in 200..2_000)
    }

    @Test fun delaysNeverShrink() {
        val all = (0 until OpenRetry.MAX_RETRIES).map { OpenRetry.delayMs(it)!! }
        assertEquals(all.sorted(), all)
    }

    @Test fun retriesAreBounded() =
        assertNull(OpenRetry.delayMs(attempt = OpenRetry.MAX_RETRIES))

    @Test fun everyAllowedAttemptHasADelay() =
        (0 until OpenRetry.MAX_RETRIES).forEach { assertNotNull(OpenRetry.delayMs(it)) }

    @Test fun negativeAttemptGivesNothing() =
        assertNull(OpenRetry.delayMs(attempt = -1))

    @Test fun wholeRetryBudgetFitsInsideTheSilenceWatchdog() {
        val total = (0 until OpenRetry.MAX_RETRIES).sumOf { OpenRetry.delayMs(it)!! }
        assertTrue(total < SessionHealth.SILENT_MS)
    }
}
