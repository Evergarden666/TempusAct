package com.jianshen.clock.core

import java.util.UUID

data class TimerEvent(
    val name: String,
    val durationSeconds: Int,
    val id: String = UUID.randomUUID().toString(),
)

data class TimerPlan(
    val name: String,
    val events: List<TimerEvent>,
    val rounds: Int = 3,
    val preparationSeconds: Int = 5,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

/** The per-run round override never changes the saved plan. */
data class RunConfig(val plan: TimerPlan, val rounds: Int = plan.rounds) {
    val cycleDurationMs: Long
        get() = plan.events.sumOf { it.durationSeconds.toLong() * 1_000L }

    val totalEventMs: Long
        get() = cycleDurationMs * rounds.toLong()

    val totalDurationMs: Long
        get() = totalEventMs + plan.preparationSeconds.toLong() * 1_000L
}

enum class SessionStatus { ACTIVE, PAUSED, RECOVERY, COMPLETED, STOPPED, INTERRUPTED }

enum class TimerPhase { PREPARATION, EVENT, COMPLETE }

data class TimerSession(
    val id: String,
    val config: RunConfig,
    val startedAtEpochMs: Long,
    val startZoneId: String,
    val elapsedEventMs: Long = 0L,
    val elapsedPreparationMs: Long = 0L,
    val status: SessionStatus = SessionStatus.ACTIVE,
    val endedAtEpochMs: Long? = null,
    val lastTickElapsedMs: Long = 0L,
) {
    val isTerminal: Boolean
        get() = status == SessionStatus.COMPLETED ||
            status == SessionStatus.STOPPED || status == SessionStatus.INTERRUPTED
}

data class TimerProgress(
    val phase: TimerPhase,
    val eventIndex: Int,
    val round: Int,
    val completedRounds: Int,
    val phaseRemainingMs: Long,
    val totalRemainingMs: Long,
    val title: String,
    val nextTitle: String,
)

/** Kept outside constructors so the editor can report invalid draft fields. */
fun validatePlan(plan: TimerPlan): String? {
    if (plan.name.trim().length !in 1..80) return "方案名称需为 1 至 80 个字符"
    if (plan.events.size !in 1..100) return "请添加 1 至 100 个事件"
    plan.events.forEachIndexed { index, event ->
        if (event.name.trim().length !in 1..80) return "事件 ${index + 1} 的名称需为 1 至 80 个字符"
        if (event.durationSeconds !in 1..86_400) return "事件 ${index + 1} 的时长需为 1 秒至 24 小时"
    }
    if (plan.rounds !in 1..999) return "循环次数需为 1 至 999 轮"
    if (plan.preparationSeconds !in 0..3_600) return "准备时间需为 0 秒至 60 分钟"
    return null
}

/** Countdown rounds up: a running phase must not display zero prematurely. */
fun formatClock(millis: Long): String {
    val seconds = if (millis <= 0L) 0L else 1L + (millis - 1L) / 1_000L
    val hours = seconds / 3_600L
    val minutes = seconds / 60L % 60L
    val remainder = seconds % 60L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${remainder.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${remainder.toString().padStart(2, '0')}"
    }
}

/** Elapsed durations round down; sub-second history is still visibly non-zero. */
fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "0 秒"
    val seconds = millis / 1_000L
    if (seconds == 0L) return "不足 1 秒"
    return buildList {
        val hours = seconds / 3_600L
        val minutes = seconds / 60L % 60L
        val remainder = seconds % 60L
        if (hours > 0L) add("$hours 小时")
        if (minutes > 0L) add("$minutes 分")
        if (remainder > 0L) add("$remainder 秒")
    }.joinToString(" ")
}
