package com.jianshen.clock.core

import org.junit.Assert.*
import org.junit.Test

class TimerEngineTest {
    private val epoch = 1_800_000_000_000L
    private fun plan(rounds: Int = 3, preparation: Int = 5, a: Int = 60, b: Int = 20) =
        TimerPlan("晨间训练", listOf(TimerEvent("运动", a), TimerEvent("休息", b)), rounds, preparation)
    private fun start(plan: TimerPlan = plan(), rounds: Int = plan.rounds) =
        TimerEngine.start(RunConfig(plan, rounds), epoch, 0L, "Asia/Shanghai", "session-1")
    private fun at(session: TimerSession, ms: Long) = TimerEngine.advance(session, ms, epoch + ms)

    @Test fun preparationOnlyRunsOnce() {
        val value = at(start(), 85_000)
        assertEquals(80_000L, value.elapsedEventMs)
        assertEquals(5000L, value.elapsedPreparationMs)
        val p = TimerEngine.progress(value)
        assertEquals(TimerPhase.EVENT, p.phase); assertEquals(2, p.round)
        assertEquals(1, p.completedRounds); assertEquals("运动", p.title)
    }
    @Test fun lateFirstCallbackCountsOnlyEventsAfterPreparation() {
        val value = at(start(plan(a = 30)), 8000)
        assertEquals(3000L, value.elapsedEventMs)
        assertEquals(27_000L, TimerEngine.progress(value).phaseRemainingMs)
    }
    @Test fun zeroPreparationStartsFirstEventImmediately() {
        val p = TimerEngine.progress(start(plan(preparation = 0)))
        assertEquals(TimerPhase.EVENT, p.phase); assertEquals(60_000L, p.phaseRemainingMs)
    }
    @Test fun exactEventBoundaryMovesToNextEvent() {
        val p = TimerEngine.progress(at(start(), 65_000))
        assertEquals(1, p.eventIndex); assertEquals("休息", p.title)
        assertEquals(20_000L, p.phaseRemainingMs)
    }
    @Test fun exactRoundBoundaryMovesToNextRound() {
        val p = TimerEngine.progress(at(start(), 85_000))
        assertEquals(2, p.round); assertEquals(0, p.eventIndex)
    }
    @Test fun allRoundsCompleteWithoutAnExtraRound() {
        val value = at(start(), 245_000)
        assertEquals(SessionStatus.COMPLETED, value.status)
        assertEquals(240_000L, value.elapsedEventMs)
        assertEquals(3, TimerEngine.progress(value).completedRounds)
    }
    @Test fun delayedCompletionClampsDurationAndEndDate() {
        val value = at(start(), 300_000)
        assertEquals(240_000L, value.elapsedEventMs)
        assertEquals(epoch + 245_000, value.endedAtEpochMs)
    }
    @Test fun delayedCallbacksCanCrossSeveralRounds() {
        val p = TimerEngine.progress(at(start(), 183_000))
        assertEquals(3, p.round); assertEquals(2, p.completedRounds)
        assertEquals(42_000L, p.phaseRemainingMs)
    }
    @Test fun pauseExcludesWaitTime() {
        val paused = TimerEngine.pause(start(plan(rounds = 1, preparation = 5, a = 30, b = 10)), 15_000, epoch + 15_000)
        val resumed = TimerEngine.resume(paused, 35_000)
        val finished = at(resumed, 65_000)
        assertEquals(40_000L, finished.elapsedEventMs)
        assertEquals(SessionStatus.COMPLETED, finished.status)
        assertEquals(epoch + 65_000, finished.endedAtEpochMs)
    }
    @Test fun pauseDuringPreparationResumesRemainingPreparation() {
        val paused = TimerEngine.pause(start(), 2000, epoch + 2000)
        val resumed = TimerEngine.resume(paused, 12_000)
        val p = TimerEngine.progress(at(resumed, 13_000))
        assertEquals(TimerPhase.PREPARATION, p.phase); assertEquals(2000L, p.phaseRemainingMs)
    }
    @Test fun tickingPausedSessionDoesNothing() {
        val paused = TimerEngine.pause(start(), 10_000, epoch + 10_000)
        assertEquals(paused, at(paused, 1_000_000))
    }
    @Test fun duplicatePauseDoesNotMoveAnchorOrCountMore() {
        val paused = TimerEngine.pause(start(), 10_000, epoch + 10_000)
        assertEquals(paused, TimerEngine.pause(paused, 100_000, epoch + 100_000))
    }
    @Test fun duplicateResumeDoesNotLoseRunningTime() {
        val running = start()
        assertEquals(running, TimerEngine.resume(running, 1000))
    }
    @Test fun pauseAtCompletionKeepsCompletedStatus() {
        assertEquals(SessionStatus.COMPLETED, TimerEngine.pause(start(), 245_000, epoch + 245_000).status)
    }
    @Test fun stopSavesPartialCurrentEventAndOnlyFullRounds() {
        val value = TimerEngine.finish(start(), 95_000, epoch + 95_000)
        assertEquals(90_000L, value.elapsedEventMs)
        assertEquals(1, TimerEngine.progress(value).completedRounds)
        assertEquals(SessionStatus.STOPPED, value.status)
    }
    @Test fun partialFirstRoundCountsZeroCompletedRounds() {
        val value = TimerEngine.finish(start(), 35_000, epoch + 35_000)
        assertEquals(30_000L, value.elapsedEventMs)
        assertEquals(0, TimerEngine.progress(value).completedRounds)
    }
    @Test fun cancellingPreparationHasNoEventDuration() {
        val value = TimerEngine.finish(start(), 3000, epoch + 3000)
        assertEquals(0L, value.elapsedEventMs)
        assertEquals(SessionStatus.STOPPED, value.status)
    }
    @Test fun stopWhilePausedDoesNotIncludePause() {
        val paused = TimerEngine.pause(start(), 35_000, epoch + 35_000)
        assertEquals(30_000L, TimerEngine.finish(paused, 80_000, epoch + 80_000).elapsedEventMs)
    }
    @Test fun stopAtFinishBoundaryIsACompletion() {
        assertEquals(SessionStatus.COMPLETED, TimerEngine.finish(start(), 245_000, epoch + 245_000).status)
    }
    @Test fun terminalUpdatesAreIdempotent() {
        val ended = at(start(), 245_000)
        assertEquals(ended, at(ended, 9_000_000))
        assertEquals(ended, TimerEngine.finish(ended, 9_000_000, epoch + 9_000_000))
        assertEquals(ended, TimerEngine.resume(ended, 9_000_000))
        assertEquals(ended, TimerEngine.recover(ended))
    }
    @Test fun recoveryDoesNotAutostartOrCountDowntime() {
        val recovered = TimerEngine.recover(at(start(), 35_000))
        assertEquals(SessionStatus.RECOVERY, recovered.status)
        assertEquals(recovered, at(recovered, 90_000))
        assertEquals(40_000L, at(TimerEngine.resume(recovered, 90_000), 100_000).elapsedEventMs)
    }
    @Test fun abandoningRecoveryIsMarkedInterrupted() {
        val recovered = TimerEngine.recover(at(start(), 35_000))
        val ended = TimerEngine.finish(recovered, 90_000, epoch + 90_000)
        assertEquals(SessionStatus.INTERRUPTED, ended.status); assertEquals(30_000L, ended.elapsedEventMs)
    }
    @Test fun recoveryAfterRebootAcceptsNewMonotonicAnchor() {
        val recovered = TimerEngine.recover(at(start(), 35_000))
        assertEquals(31_000L, at(TimerEngine.resume(recovered, 1000), 2000).elapsedEventMs)
    }
    @Test fun temporaryRoundsDoNotMutatePlan() {
        val saved = plan(rounds = 5)
        val ended = at(start(saved, rounds = 3), 245_000)
        assertEquals(5, saved.rounds); assertEquals(5, ended.config.plan.rounds)
        assertEquals(3, ended.config.rounds); assertEquals(SessionStatus.COMPLETED, ended.status)
    }
    @Test fun startSnapshotsAMutableEventList() {
        val events = mutableListOf(TimerEvent("动作", 10))
        val saved = TimerPlan("测试", events, rounds = 1, preparationSeconds = 0)
        val value = start(saved)
        events.clear()
        assertEquals(10_000L, value.config.totalEventMs)
    }
    @Test fun singleEventMultipleRoundsWorkWithSameTitle() {
        val saved = TimerPlan("单动作", listOf(TimerEvent("动作", 2)), rounds = 3, preparationSeconds = 0)
        assertEquals(2, TimerEngine.progress(at(start(saved), 2000)).round)
        assertEquals(SessionStatus.COMPLETED, at(start(saved), 6000).status)
    }
    @Test fun nextEventAtRoundEndNamesNextRound() {
        assertEquals("下一轮 · 运动", TimerEngine.progress(at(start(), 65_000)).nextTitle)
        assertEquals("完成", TimerEngine.progress(at(start(), 225_000)).nextTitle)
    }
    @Test fun wallClockChangesDoNotChangeElapsedDuration() {
        val value = TimerEngine.advance(start(), 8000, epoch - 3_600_000)
        assertEquals(3000L, value.elapsedEventMs)
    }
    @Test fun monotonicClockRegressionDoesNotDoubleCount() {
        val value = at(start(), 8000)
        assertEquals(value, at(value, 6000))
        assertEquals(4000L, at(at(value, 6000), 9000).elapsedEventMs)
    }
    @Test fun longDurationsDoNotOverflowInt() {
        val saved = TimerPlan("长计划", List(100) { TimerEvent("事件 $it", 86400) }, rounds = 999, preparationSeconds = 3600)
        assertEquals(8_631_360_000_000L, RunConfig(saved).totalEventMs)
    }
    @Test fun validatesEmptyAndOutOfRangeInputs() {
        assertNotNull(validatePlan(plan().copy(name = "   ")))
        assertNotNull(validatePlan(plan().copy(events = emptyList())))
        assertNotNull(validatePlan(plan().copy(rounds = 0)))
        assertNotNull(validatePlan(plan().copy(preparationSeconds = -1)))
        assertNotNull(validatePlan(plan().copy(events = listOf(TimerEvent("坏", 0)))))
        assertNull(validatePlan(plan()))
    }
    @Test(expected = IllegalArgumentException::class) fun startRejectsInvalidOverride() { start(plan(), 0) }
    @Test fun formattingRoundsCountdownUpAndElapsedDown() {
        assertEquals("00:01", formatClock(1)); assertEquals("00:00", formatClock(0))
        assertEquals("1:00:00", formatClock(3_600_000))
        assertEquals("不足 1 秒", formatDuration(999)); assertEquals("1 分 1 秒", formatDuration(61_999))
    }
}
