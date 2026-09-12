package com.jianshen.clock

import android.content.Context
import android.annotation.SuppressLint
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.jianshen.clock.core.*
import com.jianshen.clock.data.ClockRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.ZoneId

/** Commands are serialized on Android's main thread, shared by UI and service. */
class ClockController private constructor(context: Context) {
    private val app = context.applicationContext
    private val repository = ClockRepository.get(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val failure = MutableStateFlow<String?>(null)
    val plans = repository.plans
    val history = repository.history
    val session = repository.session
    val storageError = combine(repository.storageError, failure) { disk, command -> command ?: disk }
        .stateIn(scope, SharingStarted.Eagerly, null)
    private var lastCheckpoint = 0L

    fun savePlan(plan: TimerPlan): String? = attempt {
        check(session.value?.let { !it.isTerminal } != true) { "请先结束当前计时再编辑方案" }
        require(validatePlan(plan) == null) { validatePlan(plan).orEmpty() }
        repository.savePlan(plan.copy(name = plan.name.trim(), events = plan.events.map {
            it.copy(name = it.name.trim())
        }, updatedAt = System.currentTimeMillis()))
    }

    fun deletePlan(id: String): String? = attempt {
        check(session.value?.let { !it.isTerminal } != true) { "请先结束当前计时" }
        repository.deletePlan(id)
    }

    fun deleteHistory(recordIds: Set<String>): String? = attempt {
        repository.deleteHistory(recordIds)
    }

    fun start(plan: TimerPlan, rounds: Int): String? = attempt {
        check(session.value?.let { !it.isTerminal } != true) { "已有计时正在进行，请先结束或重置" }
        val value = TimerEngine.start(RunConfig(plan, rounds), System.currentTimeMillis(),
            SystemClock.elapsedRealtime(), ZoneId.systemDefault().id)
        repository.setSession(value)
        lastCheckpoint = SystemClock.elapsedRealtime()
        try { ensureService() } catch (error: Exception) {
            repository.setSession(null)
            throw IllegalStateException("后台计时启动失败，请回到应用后重试", error)
        }
    }

    fun pause() { attempt {
        val old = session.value ?: return@attempt
        commit(TimerEngine.pause(old, SystemClock.elapsedRealtime(), System.currentTimeMillis()))
    } }

    fun resume() { attempt {
        val old = session.value ?: return@attempt
        if (old.status != SessionStatus.PAUSED && old.status != SessionStatus.RECOVERY) return@attempt
        repository.setSession(TimerEngine.resume(old, SystemClock.elapsedRealtime()))
        try { ensureService() } catch (error: Exception) {
            repository.setSession(old)
            throw IllegalStateException("无法继续后台计时，请重新打开应用后重试", error)
        }
    } }

    fun stop() { attempt {
        val old = session.value ?: return@attempt
        if (old.isTerminal) return@attempt
        val ended = TimerEngine.finish(old, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        repository.finishSession(ended)
        if (ended.status != SessionStatus.COMPLETED) app.stopService(Intent(app, TimerService::class.java))
    } }

    fun reset() { attempt {
        val old = session.value ?: return@attempt
        if (!old.isTerminal) repository.finishSession(TimerEngine.finish(old,
            SystemClock.elapsedRealtime(), System.currentTimeMillis()))
        // The new run keeps the override but gets a fresh ID and preparation stage.
        val result = start(old.config.plan, old.config.rounds)
        check(result == null) { result.orEmpty() }
    } }

    fun restart() = reset()

    fun dismiss() { attempt {
        if (session.value?.let { !it.isTerminal } == true) return@attempt
        repository.setSession(null)
        app.stopService(Intent(app, TimerService::class.java))
    } }

    internal fun tick() { attempt {
        val old = session.value ?: return@attempt
        if (old.status != SessionStatus.ACTIVE) return@attempt
        val now = SystemClock.elapsedRealtime()
        val current = TimerEngine.advance(old, now, System.currentTimeMillis())
        if (current.isTerminal) repository.finishSession(current)
        else {
            val before = TimerEngine.progress(old)
            val after = TimerEngine.progress(current)
            val boundary = before.phase != after.phase || before.eventIndex != after.eventIndex || before.round != after.round
            val save = boundary || now - lastCheckpoint >= 1000L
            repository.setSession(current, persist = save)
            if (save) lastCheckpoint = now
        }
    } }

    internal fun checkpoint() { attempt {
        val value = session.value ?: return@attempt
        if (!value.isTerminal) repository.setSession(value)
    } }

    internal fun serviceStoppedUnexpectedly(expectedSessionId: String?) { attempt {
        val value = session.value ?: return@attempt
        if (value.id == expectedSessionId && !value.isTerminal && value.status == SessionStatus.ACTIVE)
            repository.setSession(TimerEngine.recover(value))
    } }

    internal fun serviceFailed(error: Exception) {
        failure.value = "计时服务无法继续：${error.localizedMessage.orEmpty().take(100)}"
        serviceStoppedUnexpectedly(session.value?.id)
    }

    private fun commit(value: TimerSession) {
        if (value.isTerminal) repository.finishSession(value) else repository.setSession(value)
    }

    private fun ensureService() {
        ContextCompat.startForegroundService(app, Intent(app, TimerService::class.java))
    }

    private inline fun attempt(block: () -> Unit): String? = try {
        block(); null
    } catch (error: Exception) {
        (error.localizedMessage ?: "操作失败，请重试").also { failure.value = it }
    }

    companion object {
        // The controller stores applicationContext only, never an Activity or Service.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: ClockController? = null
        fun get(context: Context): ClockController = instance ?: synchronized(this) {
            instance ?: ClockController(context).also { instance = it }
        }
    }
}
