package com.jianshen.clock.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianshen.clock.ClockController
import com.jianshen.clock.core.*

@Composable internal fun TimerScreen(value: TimerSession, controller: ClockController, onHistory: () -> Unit) {
    var confirm by rememberSaveable(value.id) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    BackHandler {
        if (value.isTerminal) controller.dismiss() else (context as? Activity)?.moveTaskToBack(true)
    }
    if (confirm != null) AlertDialog(onDismissRequest = { confirm = null },
        title = { Text(if (confirm == "reset") "从头重新开始？" else "结束本次计时？") },
        text = { Text(if (value.elapsedEventMs > 0) "已经计时的部分会保留在历史中。" else "还没有进入事件计时，不会生成历史记录。") },
        confirmButton = { TextButton(onClick = {
            if (confirm == "reset") controller.reset() else controller.stop()
            confirm = null
        }) { Text(if (confirm == "reset") "重新开始" else "结束计时") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } })
    val progress = TimerEngine.progress(value)
    if (value.isTerminal) {
        val complete = value.status == SessionStatus.COMPLETED
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Spacer(Modifier.height(26.dp))
            Tag(if (complete) "ALL DONE" else "SESSION ENDED", accent = true)
            Text(if (complete) "✓" else "◷", fontSize = 84.sp, color = if (complete) Green else Orange)
            Text(if (complete) "本次全部完成" else "本次计时已结束", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
            Text(value.config.plan.name, color = Muted, textAlign = TextAlign.Center)
            Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("实际计时", color = Muted)
                    Text(formatDuration(value.elapsedEventMs), fontSize = 28.sp, color = Orange, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("完成 ${progress.completedRounds} / ${value.config.rounds} 轮", fontSize = 16.sp)
                    Text(if (value.elapsedEventMs > 0) "已保存在本机历史中" else "仅准备阶段取消，未生成历史", color = Muted, fontSize = 12.sp)
                }
            }
            Button(onClick = controller::restart, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("按本次设置再来一次", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = controller::dismiss, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("返回我的方案") }
            if (value.elapsedEventMs > 0) TextButton(onClick = onHistory) { Text("查看本次记录") }
        }
        return
    }
    val active = value.status == SessionStatus.ACTIVE
    val preparation = progress.phase == TimerPhase.PREPARATION
    val phaseLength = if (preparation) value.config.plan.preparationSeconds.toLong() * 1000
        else value.config.plan.events[progress.eventIndex].durationSeconds.toLong() * 1000
    val fraction = (1f - progress.phaseRemainingMs.toFloat() / phaseLength.coerceAtLeast(1)).coerceIn(0f, 1f)
    val label = when (value.status) {
        SessionStatus.PAUSED -> "已暂停"
        SessionStatus.RECOVERY -> "等待恢复"
        else -> if (preparation) "准备开始" else "正在计时"
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val diameter = minOf(maxWidth - 56.dp, (maxHeight * .38f).coerceIn(180.dp, 300.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(value.config.plan.name, color = Muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Tag(label, accent = active)
            }
            if (value.status == SessionStatus.RECOVERY) {
                Text("上次计时已中断。继续时从已保存的进度开始，中断等待不计入时长。", color = Muted, fontSize = 13.sp)
            }
            Text(progress.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 39.sp)
            Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                    val stroke = 7.dp.toPx()
                    drawCircle(color = Panel, style = Stroke(stroke))
                    drawArc(color = if (active) Orange else Muted, startAngle = -90f, sweepAngle = 360f * fraction,
                        useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    val digits = formatClock(progress.phaseRemainingMs)
                    Text(digits, fontSize = (diameter.value * if (digits.length > 5) .17f else .245f).sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, color = Cream,
                        modifier = Modifier.semantics { contentDescription = "当前剩余 $digits" })
                    Text(if (preparation) "准备剩余" else "当前剩余", color = Muted, fontSize = 13.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tag("第 ${progress.round} / ${value.config.rounds} 轮", accent = true)
                Tag(if (preparation) "即将开始" else "事件 ${progress.eventIndex + 1} / ${value.config.plan.events.size}")
            }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("接下来", color = Muted, fontSize = 13.sp)
                    Text(progress.nextTitle, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("本次剩余 ${formatClock(progress.totalRemainingMs)}", color = Muted, fontSize = 13.sp)
            Button(onClick = { if (active) controller.pause() else controller.resume() },
                modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp)) {
                Text(if (active) "暂停" else "继续计时", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { confirm = "reset" }, modifier = Modifier.weight(1f).height(48.dp)) { Text("重新开始") }
                TextButton(onClick = { confirm = "stop" }, modifier = Modifier.weight(1f).height(48.dp)) { Text("结束本次", color = Muted) }
            }
            if (value.status == SessionStatus.RECOVERY) TextButton(onClick = onHistory) { Text("查看历史记录") }
        }
    }
}
