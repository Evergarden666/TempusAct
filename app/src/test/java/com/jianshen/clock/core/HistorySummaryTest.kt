package com.jianshen.clock.core

import org.junit.Assert.*
import org.junit.Test

class HistorySummaryTest {
    private val epoch = 1_800_000_000_000L
    private val workout = TimerPlan(
        name = "晨间训练",
        events = listOf(TimerEvent("运动", 60), TimerEvent("休息", 20)),
        rounds = 3,
        preparationSeconds = 5,
        id = "workout",
    )

    private fun start(
        id: String,
        plan: TimerPlan = workout,
        startedAfterMs: Long = 0L,
        rounds: Int = plan.rounds,
    ) = TimerEngine.start(RunConfig(plan, rounds), epoch + startedAfterMs, 0L, "Asia/Shanghai", id)

    private fun finish(session: TimerSession, elapsedMs: Long = session.config.totalDurationMs) =
        TimerEngine.finish(session, elapsedMs, session.startedAtEpochMs + elapsedMs)

    @Test fun emptyHistoryHasNoGroups() {
        assertTrue(groupHistoryByPlan(emptyList()).isEmpty())
    }

    @Test fun completedAndPartialRunsSumActualEventTime() {
        val completed = finish(start("completed"))
        val partial = finish(start("partial", startedAfterMs = 300_000), elapsedMs = 95_000)
        val group = groupHistoryByPlan(listOf(completed, partial)).single()

        assertEquals(SessionStatus.COMPLETED, completed.status)
        assertEquals(SessionStatus.STOPPED, partial.status)
        assertEquals(2, group.count)
        assertEquals(330_000L, group.elapsedEventMs)
    }

    @Test fun preparationAndPauseNeverInflateTheSummary() {
        val plan = workout.copy(
            events = listOf(TimerEvent("运动", 30), TimerEvent("休息", 10)),
            rounds = 1,
        )
        val initial = start("paused-run", plan)
        val paused = TimerEngine.pause(initial, 15_000, epoch + 15_000)
        val resumed = TimerEngine.resume(paused, 35_000)
        val completed = finish(resumed, 65_000)
        val group = groupHistoryByPlan(listOf(completed)).single()

        assertEquals(epoch + 65_000, completed.endedAtEpochMs)
        assertEquals(40_000L, group.elapsedEventMs)
        assertEquals(1, group.count)
    }

    @Test fun temporaryRoundCountUsesTheRunRatherThanTheSavedPlan() {
        val completed = finish(start("override", workout.copy(rounds = 5), rounds = 3))
        val group = groupHistoryByPlan(listOf(completed)).single()

        assertEquals(5, completed.config.plan.rounds)
        assertEquals(3, completed.config.rounds)
        assertEquals(240_000L, group.elapsedEventMs)
    }

    @Test fun differentPlansWithTheSameNameRemainSeparate() {
        val first = finish(start("first", workout.copy(id = "first-plan")))
        val second = finish(start("second", workout.copy(id = "second-plan")))
        val groups = groupHistoryByPlan(listOf(second, first))

        assertEquals(listOf("first-plan", "second-plan"), groups.map { it.planId })
        assertEquals(listOf("晨间训练", "晨间训练"), groups.map { it.name })
        assertEquals(listOf(1, 1), groups.map { it.count })
    }

    @Test fun renameUsesTheNewestSnapshotRegardlessOfInputOrder() {
        val beforeRename = finish(start("old", workout))
        val afterRename = finish(start("new", workout.copy(name = "力量训练"), startedAfterMs = 300_000))
        val forward = groupHistoryByPlan(listOf(beforeRename, afterRename)).single()
        val reverse = groupHistoryByPlan(listOf(afterRename, beforeRename)).single()

        assertEquals(forward, reverse)
        assertEquals("力量训练", forward.name)
        assertEquals(listOf("new", "old"), forward.records.map { it.id })
        assertEquals("晨间训练", forward.records.last().config.plan.name)
    }

    @Test fun equalStartTimesHaveAnInputIndependentSnapshotName() {
        val first = finish(start("a-session", workout.copy(name = "名字 A")))
        val second = finish(start("b-session", workout.copy(name = "名字 B")))

        assertEquals(groupHistoryByPlan(listOf(first, second)), groupHistoryByPlan(listOf(second, first)))
        assertEquals("名字 A", groupHistoryByPlan(listOf(second, first)).single().name)
    }

    @Test fun recordsSortByStartTimeEvenWhenTheEarlierRunEndedLater() {
        val earlier = finish(start("earlier"))
        val later = finish(start("later", startedAfterMs = 60_000), elapsedMs = 10_000)
        assertTrue(earlier.endedAtEpochMs!! > later.endedAtEpochMs!!)

        val group = groupHistoryByPlan(listOf(earlier, later)).single()
        assertEquals(listOf("later", "earlier"), group.records.map { it.id })
    }

    @Test fun groupsSortByTheirMostRecentUsage() {
        val olderWorkout = finish(start("older-workout"))
        val stretch = finish(start("stretch", workout.copy(id = "stretch-plan"), startedAfterMs = 300_000))
        val newerWorkout = finish(start("newer-workout", startedAfterMs = 600_000))

        val groups = groupHistoryByPlan(listOf(olderWorkout, stretch, newerWorkout))
        assertEquals(listOf("workout", "stretch-plan"), groups.map { it.planId })
        assertEquals(listOf("newer-workout", "older-workout"), groups.first().records.map { it.id })
    }

    @Test fun activePausedAndRecoverySessionsAreExcludedEvenWithEventTime() {
        val active = TimerEngine.advance(start("active"), 15_000, epoch + 15_000)
        val paused = TimerEngine.pause(start("paused"), 15_000, epoch + 15_000)
        val recovery = TimerEngine.recover(TimerEngine.advance(start("recovery"), 15_000, epoch + 15_000))
        val completed = finish(start("completed"))

        val group = groupHistoryByPlan(listOf(active, paused, recovery, completed)).single()
        assertEquals(listOf("completed"), group.records.map { it.id })
        assertEquals(240_000L, group.elapsedEventMs)
        assertTrue(groupHistoryByPlan(listOf(active, paused, recovery)).isEmpty())
    }

    @Test fun endedInterruptionCountsOnlyItsSavedEventProgress() {
        val active = TimerEngine.advance(start("interrupted"), 15_000, epoch + 15_000)
        val recovery = TimerEngine.recover(active)
        val ended = finish(recovery, 600_000)

        assertEquals(SessionStatus.INTERRUPTED, ended.status)
        assertEquals(10_000L, groupHistoryByPlan(listOf(ended)).single().elapsedEventMs)
    }

    @Test fun preparationOnlyAndImmediateCancellationsDoNotCreateGroups() {
        val preparationOnly = finish(start("preparation-only"), 3_000)
        val immediate = finish(start("immediate", workout.copy(preparationSeconds = 0)), 0L)

        assertTrue(groupHistoryByPlan(listOf(preparationOnly, immediate)).isEmpty())
    }

    @Test fun positiveSubsecondHistoryStillCounts() {
        val partial = finish(start("short", workout.copy(preparationSeconds = 0)), 150L)
        val group = groupHistoryByPlan(listOf(partial)).single()

        assertEquals(1, group.count)
        assertEquals(150L, group.elapsedEventMs)
    }

    @Test fun deletingTheNewestRecordRecalculatesNameTotalsAndGroupOrder() {
        val oldWorkout = finish(start("old-workout"))
        val stretch = finish(start("stretch", workout.copy(id = "stretch-plan"), startedAfterMs = 300_000))
        val newWorkout = finish(start("new-workout", workout.copy(name = "新版训练"), startedAfterMs = 600_000))
        val history = listOf(oldWorkout, stretch, newWorkout)
        assertEquals("新版训练", groupHistoryByPlan(history).first().name)

        val afterDeletion = groupHistoryByPlan(history.filterNot { it.id == newWorkout.id })
        assertEquals(listOf("stretch-plan", "workout"), afterDeletion.map { it.planId })
        val workoutGroup = afterDeletion.last()
        assertEquals("晨间训练", workoutGroup.name)
        assertEquals(1, workoutGroup.count)
        assertEquals(240_000L, workoutGroup.elapsedEventMs)
    }

    @Test fun deletingTheLastRecordRemovesItsGroup() {
        val first = finish(start("first"))
        val second = finish(start("second", workout.copy(id = "second-plan")))
        val remaining = groupHistoryByPlan(listOf(first, second).filterNot { it.id == first.id })

        assertEquals(listOf("second-plan"), remaining.map { it.planId })
    }

    @Test fun summedUsageDoesNotOverflowAnInteger() {
        val longPlan = workout.copy(events = listOf(TimerEvent("长事件", 86_400)), rounds = 30)
        val first = finish(start("long-first", longPlan))
        val second = finish(start("long-second", longPlan, startedAfterMs = 3_000_000_000L))

        assertEquals(5_184_000_000L, groupHistoryByPlan(listOf(first, second)).single().elapsedEventMs)
    }
}
