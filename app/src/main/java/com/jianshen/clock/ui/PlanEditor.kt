package com.jianshen.clock.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianshen.clock.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private data class EventDraft(val id: String = UUID.randomUUID().toString(), val name: String = "", val minutes: String = "0", val seconds: String = "30")
private data class EditorDraft(
    val id: String = UUID.randomUUID().toString(), val name: String = "", val rounds: String = "3", val preparation: String = "5",
    val sound: Boolean = true, val vibration: Boolean = true, val createdAt: Long = System.currentTimeMillis(),
    val events: List<EventDraft> = listOf(EventDraft(name = "运动", minutes = "1", seconds = "0"), EventDraft(name = "休息", seconds = "20")),
) {
    fun encode() = JSONObject().apply {
        put("id", id); put("name", name); put("rounds", rounds); put("preparation", preparation)
        put("sound", sound); put("vibration", vibration); put("createdAt", createdAt)
        put("events", JSONArray().apply { events.forEach { e -> put(JSONObject().apply {
            put("id", e.id); put("name", e.name); put("minutes", e.minutes); put("seconds", e.seconds)
        }) } })
    }.toString()

    fun toPlan(): TimerPlan {
        val parsed = events.mapIndexed { index, e ->
            val min = e.minutes.toLongOrNull() ?: throw IllegalArgumentException("事件 ${index + 1} 的分钟数无效")
            val sec = e.seconds.toLongOrNull() ?: throw IllegalArgumentException("事件 ${index + 1} 的秒数无效")
            require(min in 0..1440 && sec in 0..59) { "事件 ${index + 1}：分钟 0–1440，秒数 0–59" }
            val duration = min * 60 + sec
            require(duration in 1..86400) { "事件 ${index + 1} 的时长需为 1 秒至 24 小时" }
            TimerEvent(e.name.trim(), duration.toInt(), e.id)
        }
        val plan = TimerPlan(name.trim(), parsed,
            rounds.toIntOrNull() ?: throw IllegalArgumentException("请填写有效循环次数"),
            preparation.toIntOrNull() ?: throw IllegalArgumentException("请填写有效准备秒数"), sound, vibration, id, createdAt, System.currentTimeMillis())
        require(validatePlan(plan) == null) { validatePlan(plan).orEmpty() }
        return plan
    }
    companion object {
        fun from(plan: TimerPlan?): EditorDraft = plan?.let { EditorDraft(it.id, it.name, it.rounds.toString(), it.preparationSeconds.toString(),
            it.soundEnabled, it.vibrationEnabled, it.createdAt,
            it.events.map { e -> EventDraft(e.id, e.name, (e.durationSeconds / 60).toString(), (e.durationSeconds % 60).toString()) }) } ?: EditorDraft()
        fun decode(raw: String): EditorDraft = JSONObject(raw).let { j ->
            val events = j.getJSONArray("events")
            EditorDraft(j.getString("id"), j.getString("name"), j.getString("rounds"), j.getString("preparation"),
                j.getBoolean("sound"), j.getBoolean("vibration"), j.getLong("createdAt"),
                List(events.length()) { i -> events.getJSONObject(i).let { EventDraft(it.getString("id"), it.getString("name"), it.getString("minutes"), it.getString("seconds")) } })
        }
    }
}

@Composable internal fun PlanEditor(plan: TimerPlan?, onCancel: () -> Unit, onSave: (TimerPlan, Boolean) -> Unit) {
    var raw by rememberSaveable(plan?.id) { mutableStateOf(EditorDraft.from(plan).encode()) }
    val draft = remember(raw) { EditorDraft.decode(raw) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    fun update(value: EditorDraft) { raw = value.encode(); error = null }
    fun event(index: Int, value: EventDraft) { update(draft.copy(events = draft.events.toMutableList().apply { set(index, value) })) }
    fun move(index: Int, direction: Int) {
        val target = index + direction
        if (target in draft.events.indices) update(draft.copy(events = draft.events.toMutableList().apply {
            add(target, removeAt(index))
        }))
    }
    fun save(start: Boolean) {
        try { onSave(draft.toPlan(), start) } catch (problem: IllegalArgumentException) { error = problem.message }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("‹ 返回") }
            Text(if (plan == null) "创建方案" else "编辑方案", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Tag("${draft.events.size} 个事件")
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                SectionHeading(if (plan == null) "设定你的节奏" else "调整训练节奏", "按顺序执行所有事件，完成一次就是一轮。")
            }
            item { OutlinedTextField(draft.name, { if (it.length <= 80) update(draft.copy(name = it)) },
                label = { Text("方案名称") }, placeholder = { Text("例如：晨间训练") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { Text("事件顺序", style = MaterialTheme.typography.titleMedium, color = Orange) }
            itemsIndexed(draft.events, key = { _, e -> e.id }) { index, e ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Tag("${index + 1}" , accent = true)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { move(index, -1) }, enabled = index > 0, contentPadding = PaddingValues(8.dp)) { Text("上移", fontSize = 12.sp) }
                            TextButton(onClick = { move(index, 1) }, enabled = index < draft.events.lastIndex, contentPadding = PaddingValues(8.dp)) { Text("下移", fontSize = 12.sp) }
                            TextButton(onClick = { update(draft.copy(events = draft.events.filterIndexed { i, _ -> i != index })) },
                                contentPadding = PaddingValues(8.dp)) { Text("移除", fontSize = 12.sp, color = Muted) }
                        }
                        OutlinedTextField(e.name, { if (it.length <= 80) event(index, e.copy(name = it)) },
                            label = { Text("事件名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NumberField(e.minutes, { if (it.length <= 4) event(index, e.copy(minutes = it)) }, "分钟", Modifier.weight(1f))
                            NumberField(e.seconds, { if (it.length <= 2) event(index, e.copy(seconds = it)) }, "秒", Modifier.weight(1f))
                        }
                    }
                }
            }
            item { OutlinedButton(onClick = { update(draft.copy(events = draft.events + EventDraft())) }, enabled = draft.events.size < 100,
                modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("＋  添加事件") } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(draft.rounds, { if (it.length <= 4) update(draft.copy(rounds = it)) }, "循环次数", Modifier.weight(1f))
                    NumberField(draft.preparation, { if (it.length <= 4) update(draft.copy(preparation = it)) }, "开始前准备（秒）", Modifier.weight(1f))
                }
                Text("准备只执行一次，填 0 即关闭。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("提示音", modifier = Modifier.weight(1f)); Switch(draft.sound, { update(draft.copy(sound = it)) })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("震动提醒", modifier = Modifier.weight(1f)); Switch(draft.vibration, { update(draft.copy(vibration = it)) })
                        }
                    }
                }
            }
            item {
                val preview = runCatching { RunConfig(draft.toPlan()) }.getOrNull()
                if (preview != null) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("事件计时 ${formatDuration(preview.totalEventMs)}", fontWeight = FontWeight.SemiBold, color = Orange)
                    Text("单轮 ${formatDuration(preview.cycleDurationMs)} · 含准备预计 ${formatDuration(preview.totalDurationMs)}", color = Muted, fontSize = 12.sp)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { save(false) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("保存") }
                Button(onClick = { save(true) }, modifier = Modifier.weight(1.2f).height(52.dp)) { Text("保存并开始", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, label = { Text(label, fontSize = 13.sp) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier)
}
