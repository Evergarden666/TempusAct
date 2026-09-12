package com.jianshen.clock.data

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jianshen.clock.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ClockRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var repository: ClockRepository
    private fun plan() = TimerPlan("晨间训练", listOf(TimerEvent("运动", 10), TimerEvent("休息", 5)), 5, 5)
    private fun started(plan: TimerPlan = plan()) = TimerEngine.start(RunConfig(plan, 3), 1_800_000_000_000, 0, "Asia/Shanghai")
    @Before fun before() { name = "test-${UUID.randomUUID()}.db"; repository = ClockRepository(context, name) }
    @After fun after() { repository.close(); context.deleteDatabase(name) }

    @Test fun codecRoundTripRetainsOverridesAndTimezone() {
        val session = TimerEngine.pause(started(), 18_000, 1_800_000_018_000)
        assertEquals(session, JsonCodec.decodeSession(JsonCodec.encodeSession(session)))
        assertEquals(5, session.config.plan.rounds); assertEquals(3, session.config.rounds)
    }
    @Test fun multiplePlansAndEditsAreIndependent() {
        val a = plan(); val b = plan()
        repository.savePlan(a); repository.savePlan(b)
        repository.savePlan(a.copy(name = "已修改"))
        assertEquals(2, repository.plans.value.size)
        assertEquals(b, repository.plans.value.first { it.id == b.id })
    }
    @Test fun deletionKeepsHistoricalSnapshot() {
        val plan = plan(); repository.savePlan(plan)
        val ended = TimerEngine.finish(started(plan), 18_000, 1_800_000_018_000)
        repository.finishSession(ended); repository.deletePlan(plan.id)
        assertTrue(repository.plans.value.isEmpty())
        assertEquals(plan, repository.history.value.single().config.plan)
    }
    @Test fun repeatedFinishWritesOneHistory() {
        val ended = TimerEngine.finish(started(), 18_000, 1_800_000_018_000)
        repository.finishSession(ended); repository.finishSession(ended)
        assertEquals(1, repository.history.value.size)
    }
    @Test fun preparationCancellationProducesNoHistory() {
        repository.finishSession(TimerEngine.finish(started(), 2000, 1_800_000_002_000))
        assertTrue(repository.history.value.isEmpty())
    }
    @Test fun restartRecoversFrozenCheckpointWithoutAutostart() {
        val running = TimerEngine.advance(started(), 18_000, 1_800_000_018_000)
        repository.setSession(running); repository.close()
        repository = ClockRepository(context, name)
        assertEquals(SessionStatus.RECOVERY, repository.session.value!!.status)
        assertEquals(13_000L, repository.session.value!!.elapsedEventMs)
    }
    @Test fun finalizationClearsActiveAtomically() {
        val run = started(); repository.setSession(run)
        repository.finishSession(TimerEngine.finish(run, 18_000, 1_800_000_018_000))
        repository.close(); repository = ClockRepository(context, name)
        assertNull(repository.session.value); assertEquals(1, repository.history.value.size)
    }
    @Test fun lateOldCompletionDoesNotEraseANewActiveSession() {
        val old = TimerEngine.finish(started(), 18_000, 1_800_000_018_000)
        repository.finishSession(old)
        val newer = started(); repository.setSession(newer)
        repository.finishSession(old)
        assertEquals(newer.id, repository.session.value!!.id)
        repository.close(); repository = ClockRepository(context, name)
        assertEquals(newer.id, repository.session.value!!.id)
    }
    @Test fun recoveredRunAppearsUntilResumedThenFinalizesOnce() {
        val running = TimerEngine.advance(started(), 18_000, 1_800_000_018_000)
        repository.setSession(running); repository.close()
        repository = ClockRepository(context, name)
        val recovery = repository.session.value!!
        assertEquals(SessionStatus.RECOVERY, repository.history.value.single().status)
        assertEquals(running.id, repository.history.value.single().id)
        val resumed = TimerEngine.resume(recovery, 90_000)
        repository.setSession(resumed)
        assertTrue(repository.history.value.isEmpty())
        val ended = TimerEngine.finish(resumed, 92_000, 1_800_000_092_000)
        repository.finishSession(ended); repository.finishSession(ended)
        assertEquals(1, repository.history.value.size)
        assertEquals(running.id, repository.history.value.single().id)
        assertEquals(15_000L, repository.history.value.single().elapsedEventMs)
        repository.close(); repository = ClockRepository(context, name)
        assertEquals(1, repository.history.value.size); assertNull(repository.session.value)
    }
    @Test fun preparationRecoveryStaysOutOfHistory() {
        repository.setSession(TimerEngine.advance(started(), 2000, 1_800_000_002_000))
        repository.close(); repository = ClockRepository(context, name)
        assertEquals(SessionStatus.RECOVERY, repository.session.value!!.status)
        assertTrue(repository.history.value.isEmpty())
        repository.finishSession(TimerEngine.finish(repository.session.value!!, 90_000, 1_800_000_090_000))
        assertTrue(repository.history.value.isEmpty())
    }
    @Test fun endingRecoveryReplacesProvisionalEntryWithoutDuplication() {
        val recovery = TimerEngine.recover(TimerEngine.advance(started(), 18_000, 1_800_000_018_000))
        repository.setSession(recovery)
        repository.finishSession(TimerEngine.finish(recovery, 90_000, 1_800_000_090_000))
        assertEquals(SessionStatus.INTERRUPTED, repository.history.value.single().status)
        assertEquals(13_000L, repository.history.value.single().elapsedEventMs)
    }

    @Test fun deletingSelectedFinalRecordsRetainsPlansAndOtherHistory() {
        val source = plan(); repository.savePlan(source)
        val completed = TimerEngine.advance(started(source), 50_000, 1_800_000_050_000)
        val stopped = TimerEngine.finish(started(source), 18_000, 1_800_000_018_000)
        val interrupted = TimerEngine.finish(started(source), 20_000, 1_800_000_020_000, interrupted = true)
        listOf(completed, stopped, interrupted).forEach(repository::finishSession)

        repository.deleteHistory(setOf(completed.id, interrupted.id))

        assertEquals(listOf(stopped), repository.history.value)
        assertEquals(listOf(source), repository.plans.value)
        repository.close(); repository = ClockRepository(context, name)
        assertEquals(listOf(stopped), repository.history.value)
        assertEquals(listOf(source), repository.plans.value)
    }

    @Test fun deletedHistoryCannotBeResurrectedByLateFinishAfterRestart() {
        val deleted = TimerEngine.finish(started(), 18_000, 1_800_000_018_000)
        repository.finishSession(deleted)
        repository.deleteHistory(setOf(deleted.id))
        repository.deleteHistory(setOf(deleted.id))
        repository.finishSession(deleted)
        assertTrue(repository.history.value.isEmpty())

        repository.close(); repository = ClockRepository(context, name)
        val newer = started(); repository.setSession(newer)
        repository.finishSession(deleted)
        assertTrue(repository.history.value.isEmpty())
        assertEquals(newer, repository.session.value)
        repository.close(); repository = ClockRepository(context, name)
        assertTrue(repository.history.value.isEmpty())
        assertEquals(newer.id, repository.session.value!!.id)
    }

    @Test fun activeAndPausedSessionsCannotBeDeletedOrTombstoned() {
        val running = TimerEngine.advance(started(), 18_000, 1_800_000_018_000)
        val paused = TimerEngine.pause(started(), 20_000, 1_800_000_020_000)
        listOf(running, paused).forEach { session ->
            repository.setSession(session)
            assertDeletionRejected(setOf(session.id))
            assertEquals(session, repository.session.value)
            repository.finishSession(TimerEngine.finish(session, 21_000, 1_800_000_021_000))
        }
        assertEquals(setOf(running.id, paused.id), repository.history.value.map { it.id }.toSet())
    }

    @Test fun recoveryCannotBeDeletedOrPartiallyDeleteBatch() {
        val finalized = TimerEngine.finish(started(), 18_000, 1_800_000_018_000)
        repository.finishSession(finalized)
        val running = TimerEngine.advance(started(), 20_000, 1_800_000_020_000)
        repository.setSession(running)
        repository.close(); repository = ClockRepository(context, name)
        val recovery = repository.session.value!!

        assertDeletionRejected(linkedSetOf(finalized.id, recovery.id))

        assertEquals(recovery, repository.session.value)
        assertEquals(setOf(finalized.id, recovery.id), repository.history.value.map { it.id }.toSet())
        repository.finishSession(TimerEngine.finish(recovery, 90_000, 1_800_000_090_000))
        assertEquals(2, repository.history.value.size)
        assertTrue(repository.history.value.all { it.isTerminal })
    }

    @Test fun emptyAndUnknownDeletesDoNotPreventAFutureSessionFromBeingSaved() {
        val future = TimerEngine.finish(started(), 18_000, 1_800_000_018_000)
        repository.deleteHistory(emptySet())
        repository.deleteHistory(setOf(future.id))
        assertNull(repository.storageError.value)

        repository.finishSession(future)

        assertEquals(listOf(future), repository.history.value)
    }

    @Test fun versionOneUpgradePreservesPlansHistoryAndActiveCheckpoint() {
        val first = plan(); val second = plan().copy(name = "第二套方案")
        val finalized = TimerEngine.finish(started(first), 18_000, 1_800_000_018_000)
        val running = TimerEngine.advance(started(second), 20_000, 1_800_000_020_000)
        repository.close()
        assertTrue(context.deleteDatabase(name))
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE plans(id TEXT PRIMARY KEY NOT NULL,json TEXT NOT NULL,updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE history(id TEXT PRIMARY KEY NOT NULL,json TEXT NOT NULL,started_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE active(slot INTEGER PRIMARY KEY CHECK(slot=1),json TEXT NOT NULL)")
            listOf(first, second).forEach {
                db.execSQL("INSERT INTO plans(id,json,updated_at) VALUES(?,?,?)",
                    arrayOf<Any>(it.id, JsonCodec.encodePlan(it), it.updatedAt))
            }
            db.execSQL("INSERT INTO history(id,json,started_at) VALUES(?,?,?)",
                arrayOf<Any>(finalized.id, JsonCodec.encodeSession(finalized), finalized.startedAtEpochMs))
            db.execSQL("INSERT INTO active(slot,json) VALUES(1,?)", arrayOf<Any>(JsonCodec.encodeSession(running)))
            db.version = 1
        }

        repository = ClockRepository(context, name)

        assertNull(repository.storageError.value)
        assertEquals(setOf(first, second), repository.plans.value.toSet())
        assertEquals(finalized, repository.history.value.single { it.id == finalized.id })
        assertEquals(TimerEngine.recover(running), repository.session.value)
        context.openOrCreateDatabase(name, 0, null).use { db -> assertEquals(2, db.version) }
        repository.deleteHistory(setOf(finalized.id))
        repository.finishSession(finalized)
        assertEquals(listOf(TimerEngine.recover(running)), repository.history.value)
        assertEquals(TimerEngine.recover(running), repository.session.value)
    }

    private fun assertDeletionRejected(recordIds: Set<String>) {
        try {
            repository.deleteHistory(recordIds)
            fail("An active or recoverable session must not be deleted")
        } catch (_: IllegalArgumentException) {
            assertNotNull(repository.storageError.value)
        }
    }
}
