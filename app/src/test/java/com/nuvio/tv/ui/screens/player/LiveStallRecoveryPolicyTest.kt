package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStallRecoveryPolicyTest {

    private val t0 = 1_000_000L

    @Test
    fun `below threshold keeps waiting`() {
        val action = LiveStallRecoveryPolicy.evaluate(
            LiveStallRecoveryPolicy.Input(
                stalledForMs = LiveStallRecoveryPolicy.LIVE_STALL_THRESHOLD_MS - 1,
                attempts = emptyList(),
                nowMs = t0
            )
        )
        assertEquals(LiveStallRecoveryPolicy.Action.NONE, action)
    }

    @Test
    fun `first violation rejoins the live edge`() {
        val action = LiveStallRecoveryPolicy.evaluate(
            LiveStallRecoveryPolicy.Input(stalledForMs = 15_000L, attempts = emptyList(), nowMs = t0)
        )
        assertEquals(LiveStallRecoveryPolicy.Action.REJOIN_LIVE_EDGE, action)
    }

    @Test
    fun `second violation reconnects the source`() {
        val action = LiveStallRecoveryPolicy.evaluate(
            LiveStallRecoveryPolicy.Input(
                stalledForMs = 15_000L,
                attempts = listOf(t0 - 10_000L),
                nowMs = t0
            )
        )
        assertEquals(LiveStallRecoveryPolicy.Action.RECONNECT_SOURCE, action)
    }

    @Test
    fun `exhausted window parks instead of hammering the server`() {
        val attempts = listOf(t0 - 110_000L, t0 - 60_000L, t0 - 10_000L)
        val action = LiveStallRecoveryPolicy.evaluate(
            LiveStallRecoveryPolicy.Input(stalledForMs = 15_000L, attempts = attempts, nowMs = t0)
        )
        assertEquals(LiveStallRecoveryPolicy.Action.NONE, action)
    }

    @Test
    fun `old attempts outside the window restart the ladder`() {
        val stale = listOf(t0 - LiveStallRecoveryPolicy.RECOVERY_WINDOW_MS - 1)
        val action = LiveStallRecoveryPolicy.evaluate(
            LiveStallRecoveryPolicy.Input(stalledForMs = 15_000L, attempts = stale, nowMs = t0)
        )
        assertEquals(LiveStallRecoveryPolicy.Action.REJOIN_LIVE_EDGE, action)
    }

    @Test
    fun `nextAttempts records attempt and prunes the window`() {
        val attempts = listOf(t0 - LiveStallRecoveryPolicy.RECOVERY_WINDOW_MS - 5_000L, t0 - 30_000L)
        val next = LiveStallRecoveryPolicy.nextAttempts(attempts, t0)
        assertEquals(listOf(t0 - 30_000L, t0), next)
    }

    @Test
    fun `resetNeeded only when the whole window expired`() {
        assertTrue(
            LiveStallRecoveryPolicy.resetNeeded(
                listOf(t0 - LiveStallRecoveryPolicy.RECOVERY_WINDOW_MS - 1),
                t0
            )
        )
        assertFalse(
            LiveStallRecoveryPolicy.resetNeeded(
                listOf(t0 - LiveStallRecoveryPolicy.RECOVERY_WINDOW_MS + 1),
                t0
            )
        )
        assertFalse(LiveStallRecoveryPolicy.resetNeeded(emptyList(), t0))
    }

    @Test
    fun `stalledForMs at exactly the threshold acts`() {
        val action = LiveStallRecoveryPolicy.evaluate(
            LiveStallRecoveryPolicy.Input(
                stalledForMs = LiveStallRecoveryPolicy.LIVE_STALL_THRESHOLD_MS,
                attempts = emptyList(),
                nowMs = t0
            )
        )
        assertEquals(LiveStallRecoveryPolicy.Action.REJOIN_LIVE_EDGE, action)
    }
}
