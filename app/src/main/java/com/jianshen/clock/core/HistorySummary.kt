package com.jianshen.clock.core

data class PlanHistoryGroup(
    val planId: String,
    val name: String,
    val records: List<TimerSession>,
) {
    val elapsedEventMs: Long
        get() = records.sumOf { it.elapsedEventMs }

    val count: Int
        get() = records.size
}

/**
 * Summarizes completed usage from its historical snapshots, independently of the
 * current plan library. Preparation-only cancellations and unfinished sessions
 * are not formal history and therefore do not contribute to these groups.
 */
fun groupHistoryByPlan(history: List<TimerSession>): List<PlanHistoryGroup> = history
    .filter { it.isTerminal && it.elapsedEventMs > 0L }
    .groupBy { it.config.plan.id }
    .map { (planId, records) ->
        // Session IDs provide a stable tie break when two starts share a timestamp.
        val newestFirst = records.sortedWith(
            compareByDescending<TimerSession> { it.startedAtEpochMs }.thenBy { it.id },
        )
        PlanHistoryGroup(planId, newestFirst.first().config.plan.name, newestFirst)
    }
    .sortedWith(
        compareByDescending<PlanHistoryGroup> { it.records.first().startedAtEpochMs }.thenBy { it.planId },
    )
