package com.jianshen.clock.data

import com.jianshen.clock.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/** Versioned values retain a complete per-run snapshot, including round overrides. */
object JsonCodec {
    fun encodePlan(plan: TimerPlan): String = planJson(plan).toString()
    fun decodePlan(json: String): TimerPlan = readPlan(JSONObject(json))

    private fun planJson(plan: TimerPlan) = JSONObject().apply {
        put("version", 1); put("id", plan.id); put("name", plan.name)
        put("rounds", plan.rounds); put("preparationSeconds", plan.preparationSeconds)
        put("soundEnabled", plan.soundEnabled); put("vibrationEnabled", plan.vibrationEnabled)
        put("createdAt", plan.createdAt); put("updatedAt", plan.updatedAt)
        put("events", JSONArray().apply {
            plan.events.forEach { event -> put(JSONObject().apply {
                put("id", event.id); put("name", event.name); put("durationSeconds", event.durationSeconds)
            }) }
        })
    }

    private fun readPlan(json: JSONObject): TimerPlan {
        require(json.optInt("version", 1) == 1) { "不支持的方案版本" }
        val events = json.getJSONArray("events")
        val plan = TimerPlan(
            id = json.getString("id"), name = json.getString("name"),
            events = List(events.length()) { index -> events.getJSONObject(index).let {
                TimerEvent(it.getString("name"), it.getInt("durationSeconds"), it.getString("id"))
            } },
            rounds = json.getInt("rounds"), preparationSeconds = json.getInt("preparationSeconds"),
            soundEnabled = json.getBoolean("soundEnabled"), vibrationEnabled = json.getBoolean("vibrationEnabled"),
            createdAt = json.getLong("createdAt"), updatedAt = json.getLong("updatedAt"),
        )
        require(plan.id.isNotBlank() && plan.events.map { it.id }.distinct().size == plan.events.size)
        require(validatePlan(plan) == null) { validatePlan(plan).orEmpty() }
        return plan
    }

    fun encodeSession(session: TimerSession): String = JSONObject().apply {
        put("version", 1); put("id", session.id); put("plan", planJson(session.config.plan))
        put("rounds", session.config.rounds); put("startedAtEpochMs", session.startedAtEpochMs)
        put("startZoneId", session.startZoneId); put("elapsedEventMs", session.elapsedEventMs)
        put("elapsedPreparationMs", session.elapsedPreparationMs); put("status", session.status.name)
        put("endedAtEpochMs", session.endedAtEpochMs ?: JSONObject.NULL)
        put("lastTickElapsedMs", session.lastTickElapsedMs)
    }.toString()

    fun decodeSession(raw: String): TimerSession {
        val json = JSONObject(raw)
        require(json.optInt("version", 1) == 1) { "不支持的计时记录版本" }
        val config = RunConfig(readPlan(json.getJSONObject("plan")), json.getInt("rounds"))
        require(config.rounds in 1..999)
        val session = TimerSession(
            id = json.getString("id"), config = config,
            startedAtEpochMs = json.getLong("startedAtEpochMs"), startZoneId = json.getString("startZoneId"),
            elapsedEventMs = json.getLong("elapsedEventMs"),
            elapsedPreparationMs = json.getLong("elapsedPreparationMs"),
            status = SessionStatus.valueOf(json.getString("status")),
            endedAtEpochMs = if (json.isNull("endedAtEpochMs")) null else json.getLong("endedAtEpochMs"),
            lastTickElapsedMs = json.getLong("lastTickElapsedMs"),
        )
        require(session.id.isNotBlank())
        ZoneId.of(session.startZoneId)
        require(session.elapsedEventMs in 0..config.totalEventMs)
        require(session.elapsedPreparationMs in 0..config.plan.preparationSeconds.toLong() * 1000)
        require(session.elapsedEventMs == 0L || session.elapsedPreparationMs == config.plan.preparationSeconds.toLong() * 1000)
        require(session.status != SessionStatus.COMPLETED || session.elapsedEventMs == config.totalEventMs)
        return session
    }
}
