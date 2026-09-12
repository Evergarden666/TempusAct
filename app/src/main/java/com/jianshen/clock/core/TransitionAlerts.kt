package com.jianshen.clock.core

/** Paused observations must not consume an event's transition reminder. */
class TransitionAlerts {
    private data class EventKey(val sessionId: String, val round: Int, val index: Int)
    private var lastAlerted: EventKey? = null

    fun signalIfNeeded(session: TimerSession, signal: () -> Unit) {
        if (session.status != SessionStatus.ACTIVE) return
        val progress = TimerEngine.progress(session)
        if (progress.phase != TimerPhase.EVENT) return
        val key = EventKey(session.id, progress.round, progress.eventIndex)
        if (key == lastAlerted) return
        signal()
        lastAlerted = key
    }
}
