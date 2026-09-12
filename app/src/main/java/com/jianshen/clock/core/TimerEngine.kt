package com.jianshen.clock.core

import java.util.UUID

/**
 * Pure elapsed-time state machine. Android owns the clocks and persistence.
 * Advancing uses one accumulated event duration instead of per-tick decrements,
 * so delayed callbacks can cross any number of event and round boundaries.
 */
object TimerEngine {
    fun start(
        config: RunConfig,
        nowEpochMs: Long,
        nowElapsedMs: Long,
        zoneId: String,
        id: String = UUID.randomUUID().toString(),
    ): TimerSession {
        require(validatePlan(config.plan) == null) { validatePlan(config.plan).orEmpty() }
        require(config.rounds in 1..999) { "本次循环次数需为 1 至 999 轮" }
        require(nowElapsedMs >= 0L) { "单调时钟不能为负数" }
        // Copy the list as well as its value objects; an editor may own a mutable list.
        val snapshot = config.copy(plan = config.plan.copy(events = config.plan.events.map { it.copy() }))
        return TimerSession(
            id = id,
            config = snapshot,
            startedAtEpochMs = nowEpochMs,
            startZoneId = zoneId,
            lastTickElapsedMs = nowElapsedMs,
        )
    }

    fun advance(session: TimerSession, nowElapsedMs: Long, nowEpochMs: Long): TimerSession {
        if (session.status != SessionStatus.ACTIVE || nowElapsedMs <= session.lastTickElapsedMs) {
            return session
        }
        val delta = nowElapsedMs - session.lastTickElapsedMs
        val preparationMs = session.config.plan.preparationSeconds.toLong() * 1_000L
        val preparationRemaining = (preparationMs - session.elapsedPreparationMs).coerceAtLeast(0L)
        val preparationDelta = minOf(delta, preparationRemaining)
        val eventRemaining = (session.config.totalEventMs - session.elapsedEventMs).coerceAtLeast(0L)
        val eventDelta = minOf(delta - preparationDelta, eventRemaining)
        val elapsedEvents = session.elapsedEventMs + eventDelta
        val completed = elapsedEvents >= session.config.totalEventMs
        return session.copy(
            elapsedPreparationMs = session.elapsedPreparationMs + preparationDelta,
            elapsedEventMs = elapsedEvents,
            status = if (completed) SessionStatus.COMPLETED else SessionStatus.ACTIVE,
            // A callback arriving late must not add its delay to the finish date.
            endedAtEpochMs = if (completed) nowEpochMs - (delta - preparationDelta - eventDelta) else null,
            lastTickElapsedMs = nowElapsedMs,
        )
    }

    fun pause(session: TimerSession, nowElapsedMs: Long, nowEpochMs: Long): TimerSession {
        val advanced = advance(session, nowElapsedMs, nowEpochMs)
        return if (advanced.status == SessionStatus.ACTIVE) {
            advanced.copy(status = SessionStatus.PAUSED)
        } else advanced
    }

    fun resume(session: TimerSession, nowElapsedMs: Long): TimerSession {
        if (session.status != SessionStatus.PAUSED && session.status != SessionStatus.RECOVERY) return session
        require(nowElapsedMs >= 0L) { "单调时钟不能为负数" }
        // Reset the anchor, excluding both pause time and unobserved interruption time.
        return session.copy(status = SessionStatus.ACTIVE, lastTickElapsedMs = nowElapsedMs)
    }

    fun recover(session: TimerSession): TimerSession =
        if (session.isTerminal || session.status == SessionStatus.RECOVERY) session
        else session.copy(status = SessionStatus.RECOVERY)

    fun finish(
        session: TimerSession,
        nowElapsedMs: Long,
        nowEpochMs: Long,
        interrupted: Boolean = false,
    ): TimerSession {
        val advanced = advance(session, nowElapsedMs, nowEpochMs)
        if (advanced.isTerminal) return advanced
        return advanced.copy(
            status = if (interrupted || advanced.status == SessionStatus.RECOVERY) {
                SessionStatus.INTERRUPTED
            } else SessionStatus.STOPPED,
            endedAtEpochMs = nowEpochMs,
        )
    }

    fun progress(session: TimerSession): TimerProgress {
        val config = session.config
        val preparationRemaining =
            (config.plan.preparationSeconds.toLong() * 1_000L - session.elapsedPreparationMs).coerceAtLeast(0L)
        val elapsedEvents = session.elapsedEventMs.coerceIn(0L, config.totalEventMs)
        val eventRemaining = config.totalEventMs - elapsedEvents
        if (elapsedEvents == config.totalEventMs) {
            return TimerProgress(
                phase = TimerPhase.COMPLETE,
                eventIndex = config.plan.events.lastIndex,
                round = config.rounds,
                completedRounds = config.rounds,
                phaseRemainingMs = 0L,
                totalRemainingMs = 0L,
                title = "全部完成",
                nextTitle = "",
            )
        }
        if (preparationRemaining > 0L) {
            return TimerProgress(
                phase = TimerPhase.PREPARATION,
                eventIndex = 0,
                round = 1,
                completedRounds = 0,
                phaseRemainingMs = preparationRemaining,
                totalRemainingMs = preparationRemaining + eventRemaining,
                title = "准备",
                nextTitle = config.plan.events.first().name,
            )
        }
        val completedRounds = (elapsedEvents / config.cycleDurationMs).toInt()
        var cycleOffset = elapsedEvents % config.cycleDurationMs
        config.plan.events.forEachIndexed { index, event ->
            val durationMs = event.durationSeconds.toLong() * 1_000L
            if (cycleOffset < durationMs) {
                val nextTitle = when {
                    index < config.plan.events.lastIndex -> config.plan.events[index + 1].name
                    completedRounds + 1 < config.rounds -> "下一轮 · ${config.plan.events.first().name}"
                    else -> "完成"
                }
                return TimerProgress(
                    phase = TimerPhase.EVENT,
                    eventIndex = index,
                    round = completedRounds + 1,
                    completedRounds = completedRounds,
                    phaseRemainingMs = durationMs - cycleOffset,
                    totalRemainingMs = eventRemaining,
                    title = event.name,
                    nextTitle = nextTitle,
                )
            }
            cycleOffset -= durationMs
        }
        error("计时配置无有效事件")
    }
}
