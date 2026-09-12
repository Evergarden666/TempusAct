package com.jianshen.clock.core

import org.junit.Assert.*
import org.junit.Test

class TransitionAlertsTest {
    private val alerts = TransitionAlerts()
    private val seen = mutableListOf<TimerProgress>()
    private fun start(preparation: Int = 0) = TimerEngine.start(
        RunConfig(TimerPlan("训练", listOf(TimerEvent("休息", 10), TimerEvent("休息", 5)),
            rounds = 3, preparationSeconds = preparation)), 0, 0, "Asia/Shanghai")
    private fun observe(value: TimerSession) = alerts.signalIfNeeded(value) { seen += TimerEngine.progress(value) }

    @Test fun initialEventSignalsOnce() {
        val value = start()
        observe(value); observe(TimerEngine.advance(value, 500, 500))
        assertEquals(1, seen.size)
    }
    @Test fun preparationBoundaryPauseDoesNotConsumeFirstEventAlert() {
        val value = start(5); observe(value)
        val paused = TimerEngine.pause(value, 5_000, 5_000); observe(paused)
        assertTrue(seen.isEmpty())
        observe(TimerEngine.resume(paused, 20_000))
        assertEquals(1, seen.size); assertEquals(0, seen.single().eventIndex)
    }
    @Test fun eventBoundaryPauseAlertsNextEventOnResume() {
        val value = start(); observe(value)
        val paused = TimerEngine.pause(value, 10_000, 10_000); observe(paused)
        assertEquals(1, seen.size)
        observe(TimerEngine.resume(paused, 20_000))
        assertEquals(listOf(0, 1), seen.map { it.eventIndex })
    }
    @Test fun ordinaryPauseResumeDoesNotRepeatReminder() {
        val value = start(); observe(value)
        val paused = TimerEngine.pause(value, 2_000, 2_000); observe(paused)
        observe(TimerEngine.resume(paused, 20_000))
        assertEquals(1, seen.size)
    }
    @Test fun sameNameEventsAndNewRoundAreDifferentTransitions() {
        val value = start(); observe(value)
        observe(TimerEngine.advance(value, 10_000, 10_000))
        observe(TimerEngine.advance(value, 15_000, 15_000))
        assertEquals(listOf(1 to 0, 1 to 1, 2 to 0), seen.map { it.round to it.eventIndex })
    }
    @Test fun delayedCallbackAlertsOnlyCurrentEvent() {
        val value = start(); observe(value)
        observe(TimerEngine.advance(value, 26_000, 26_000))
        assertEquals(2, seen.size)
        assertEquals(2, seen.last().round); assertEquals(1, seen.last().eventIndex)
    }
    @Test fun newSessionAtSamePositionStillAlerts() {
        observe(start()); observe(start())
        assertEquals(2, seen.size)
    }
    @Test fun terminalAndRecoveryStatesDoNotAlert() {
        val value = start()
        observe(TimerEngine.recover(value)); observe(TimerEngine.finish(value, 1000, 1000))
        observe(TimerEngine.advance(value, 45_000, 45_000))
        assertTrue(seen.isEmpty())
    }
    @Test fun failedSignalCanBeRetried() {
        val value = start()
        try { alerts.signalIfNeeded(value) { error("unavailable") }; fail("Expected signal failure") }
        catch (_: IllegalStateException) { }
        observe(value)
        assertEquals(1, seen.size)
    }
}
