package com.jianshen.clock.ui

import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianshen.clock.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun statusLabel(status: SessionStatus) = when (status) {
    SessionStatus.COMPLETED -> "已完成"
    SessionStatus.STOPPED -> "提前结束"
    SessionStatus.INTERRUPTED -> "已中断"
    SessionStatus.RECOVERY -> "待恢复"
    SessionStatus.PAUSED -> "已暂停"
    SessionStatus.ACTIVE -> "进行中"
}

private fun localTime(value: TimerSession, epoch: Long = value.startedAtEpochMs, pattern: String): String =
    Instant.ofEpochMilli(epoch).atZone(ZoneId.of(value.startZoneId)).format(DateTimeFormatter.ofPattern(pattern))

@Composable internal fun HistoryScreen(
    history: List<TimerSession>,
    onDelete: (Set<String>) -> String?,
    onOpen: (TimerSession) -> Unit,
) {
    val groups = remember(history) { groupHistoryByPlan(history) }
    var mode by rememberSaveable { mutableStateOf("groups") }
    var planId by rememberSaveable { mutableStateOf<String?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var pendingDelete by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val group = groups.firstOrNull { it.planId == planId }
    val overview = mode == "groups" && group == null
    val finalized = remember(history) {
        history.filter { it.isTerminal && it.elapsedEventMs > 0L }
            .sortedWith(compareByDescending<TimerSession> { it.startedAtEpochMs }.thenBy { it.id })
    }
    val records = group?.records ?: finalized
    val availableIds = records.map { it.id }.toSet()
    val selected = selectedIds.toSet().intersect(availableIds)
    val pending = history.filter { !it.isTerminal && (group == null || it.config.plan.id == group.planId) }
    val listState = rememberLazyListState()
    fun clearSelection() { selecting = false; selectedIds = emptyList() }
    fun showMode(value: String) { mode = value; planId = null; clearSelection() }
    LaunchedEffect(planId, groups) {
        if (planId != null && group == null) { planId = null; clearSelection() }
    }
    LaunchedEffect(mode, planId) { listState.scrollToItem(0) }
    BackHandler(enabled = selecting || planId != null) {
        if (selecting) clearSelection() else planId = null
    }
    if (pendingDelete.isNotEmpty()) DeleteRecordsDialog(
        count = pendingDelete.size,
        onDismiss = { pendingDelete = emptyList() },
        onDelete = { onDelete(pendingDelete.toSet()) },
        onSuccess = { pendingDelete = emptyList(); clearSelection() },
    )
    Column(Modifier.fillMaxSize()) {
        if (group != null) TextButton(onClick = { planId = null; clearSelection() },
            modifier = Modifier.padding(horizontal = 12.dp)) { Text("‹ 所有分组") }
        if (selecting) Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("已选 ${selected.size} 条", modifier = Modifier.weight(1f), color = Orange)
            TextButton(onClick = { selectedIds = if (selected.size == availableIds.size) emptyList() else availableIds.toList() }) {
                Text(if (selected.size == availableIds.size && selected.isNotEmpty()) "取消全选" else "全选")
            }
            TextButton(onClick = ::clearSelection) { Text("取消") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                SectionHeading(group?.name ?: "计时历史", if (group != null) "这套方案的每一次计时。" else "按方案汇总，看看时间花在哪里。")
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(selected = mode == "groups", onClick = { showMode("groups") }, label = { Text("按方案分组") })
                    FilterChip(selected = mode == "all", onClick = { showMode("all") }, label = { Text("全部记录") })
                }
            }
            item {
                HistoryTotal(if (group != null) "本组总用时" else "全部总用时",
                    group?.elapsedEventMs ?: groups.sumOf { it.elapsedEventMs }, group?.count ?: finalized.size)
            }
            if (pending.isNotEmpty()) {
                item { Text("待恢复计时", color = Muted, fontSize = 13.sp) }
                items(pending, key = { "pending-${it.id}" }) { record ->
                    HistoryRecordCard(record, onClick = { onOpen(record) })
                }
                item { Text("待恢复记录不计入汇总，结束后可删除。", color = Muted, fontSize = 12.sp) }
            }
            if (overview) {
                item { Text("${groups.size} 个方案分组", style = MaterialTheme.typography.titleMedium) }
                if (groups.isEmpty()) item { EmptyHistory() }
                items(groups, key = { "group-${it.planId}" }) { item ->
                    Card(Modifier.fillMaxWidth().clickable { planId = item.planId; clearSelection() },
                        colors = CardDefaults.cardColors(containerColor = Panel)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(item.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Tag("${item.count} 次")
                            }
                            Text(formatDuration(item.elapsedEventMs), color = Orange, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                            Text("实际总用时", color = Muted, fontSize = 12.sp)
                            Text(item.records.first().config.plan.events.joinToString(" → ") { it.name },
                                color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                            Text("最近 ${localTime(item.records.first(), pattern = "yyyy-MM-dd HH:mm")}  ›", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${records.size} 条已结束记录", modifier = Modifier.weight(1f), color = Muted, fontSize = 13.sp)
                        if (!selecting && records.isNotEmpty()) TextButton(onClick = { selecting = true }) { Text("选择记录") }
                    }
                }
                if (records.isEmpty()) item { EmptyHistory() }
                records.groupBy { localTime(it, pattern = "yyyy年M月d日") }.forEach { (date, entries) ->
                    item(key = "date-$date") { Text(date, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
                    items(entries, key = { it.id }) { record ->
                        HistoryRecordCard(record, selecting = selecting, selected = record.id in selected) {
                            if (selecting) selectedIds = if (record.id in selected) selectedIds - record.id else selectedIds + record.id
                            else onOpen(record)
                        }
                    }
                }
            }
        }
        if (selecting) Button(onClick = { pendingDelete = selected.toList() }, enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp).heightIn(min = 50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
            Text("删除所选 ${selected.size} 条记录")
        }
    }
}

@Composable private fun HistoryTotal(label: String, millis: Long, count: Int) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, color = Muted)
            Text(formatDuration(millis), color = Orange, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("$count 次已结束计时 · 仅保存在本机", color = Muted, fontSize = 12.sp)
            Text("含休息事件，不含准备、暂停和中断等待。", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable private fun EmptyHistory() {
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("还没有已结束的记录", style = MaterialTheme.typography.titleLarge)
            Text("完成或提前结束一次计时后，会自动按使用的方案汇总。", color = Muted)
        }
    }
}

@Composable private fun HistoryRecordCard(
    record: TimerSession, selecting: Boolean = false, selected: Boolean = false, onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selecting) Checkbox(checked = selected, onCheckedChange = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(record.config.plan.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 19.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Tag(statusLabel(record.status), accent = record.status == SessionStatus.COMPLETED)
                }
                Text(formatDuration(record.elapsedEventMs), color = Orange, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                Text("${localTime(record, pattern = "HH:mm")} 开始 · 完成 ${TimerEngine.progress(record).completedRounds}/${record.config.rounds} 轮",
                    color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable private fun DeleteRecordsDialog(count: Int, onDismiss: () -> Unit, onDelete: () -> String?, onSuccess: () -> Unit) {
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "删除这条计时记录？" else "删除这 $count 条记录？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("删除后无法恢复，分组总用时会同步更新。循环方案和其他记录会保留。")
                if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = {
            error = onDelete()
            if (error == null) onSuccess()
        }) { Text("删除记录", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable internal fun HistoryDetail(value: TimerSession, onDelete: (Set<String>) -> String?, onBack: () -> Unit) {
    var confirmDelete by rememberSaveable(value.id) { mutableStateOf(false) }
    if (confirmDelete) DeleteRecordsDialog(1, onDismiss = { confirmDelete = false },
        onDelete = { onDelete(setOf(value.id)) }, onSuccess = { confirmDelete = false; onBack() })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { TextButton(onClick = onBack) { Text("‹ 返回历史") } }
        item { SectionHeading(value.config.plan.name, localTime(value, pattern = "yyyy年M月d日")) }
        item { Tag(statusLabel(value.status), accent = true) }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("实际计时", color = Muted)
                    Text(formatDuration(value.elapsedEventMs), color = Orange, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text("含事件中的休息时间，不含开始前准备和暂停。", color = Muted, fontSize = 12.sp)
                    HorizontalDivider(color = Muted.copy(alpha = .2f))
                    Text("完成 ${TimerEngine.progress(value).completedRounds} / ${value.config.rounds} 轮")
                    Text("本次计划计时 ${formatDuration(value.config.totalEventMs)}", color = Muted, fontSize = 14.sp)
                    Text("开始前准备 ${value.config.plan.preparationSeconds} 秒", color = Muted, fontSize = 14.sp)
                }
            }
        }
        item {
            Text("开始  ${localTime(value, pattern = "yyyy-MM-dd HH:mm:ss")}", color = Muted, fontSize = 13.sp)
            value.endedAtEpochMs?.let { Text("结束  ${localTime(value, it, "yyyy-MM-dd HH:mm:ss")}", color = Muted, fontSize = 13.sp) }
        }
        item { Text("本次使用的事件", style = MaterialTheme.typography.titleMedium) }
        items(value.config.plan.events, key = { it.id }) { event ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(event.name, modifier = Modifier.weight(1f))
                Text(formatClock(event.durationSeconds.toLong() * 1000), color = Orange)
            }
        }
        item { Text("这里保留的是开始时的设置。之后编辑或删除方案，不会改变这条记录。", color = Muted, fontSize = 12.sp) }
        if (value.isTerminal) item {
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                Text("删除这条记录", color = MaterialTheme.colorScheme.error)
            }
        } else item { Text("此记录尚待恢复，结束本次计时后可以删除。", color = Muted, fontSize = 12.sp) }
    }
}
