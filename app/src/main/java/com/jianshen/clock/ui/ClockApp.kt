package com.jianshen.clock.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.jianshen.clock.ClockController
import com.jianshen.clock.R
import com.jianshen.clock.core.*
import com.jianshen.clock.data.JsonCodec
import kotlinx.coroutines.launch

@Composable fun ClockApp(controller: ClockController) = ClockTheme {
    val plans by controller.plans.collectAsState()
    val history by controller.history.collectAsState()
    val session by controller.session.collectAsState()
    val storageError by controller.storageError.collectAsState()
    var tab by rememberSaveable { mutableStateOf("plans") }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingJson by rememberSaveable { mutableStateOf<String?>(null) }
    var firstSetupShown by rememberSaveable { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingStart by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRounds by rememberSaveable { mutableIntStateOf(1) }
    var recoveryHistory by rememberSaveable(session?.id) { mutableStateOf(false) }
    val historyState = rememberSaveableStateHolder()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    fun launchPending() {
        val raw = pendingStart ?: return
        pendingStart = null
        controller.start(JsonCodec.decodePlan(raw), pendingRounds)?.let(::message)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        launchPending()
        if (!granted) message("计时仍可使用；允许通知后可在锁屏通知中暂停或继续。")
    }
    fun start(plan: TimerPlan, rounds: Int) {
        pendingStart = JsonCodec.encodePlan(plan); pendingRounds = rounds
        val prefs = context.getSharedPreferences("interface", 0)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !prefs.getBoolean("notificationAsked", false)) {
            prefs.edit { putBoolean("notificationAsked", true) }
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else launchPending()
    }
    LaunchedEffect(Unit) {
        if (!firstSetupShown) {
            firstSetupShown = true
            if (plans.isEmpty() && session == null && storageError == null) editing = true
        }
    }
    val browsingRecovery = session?.status == SessionStatus.RECOVERY && recoveryHistory
    val showingTimer = session != null && !browsingRecovery
    if (browsingRecovery) BackHandler {
        if (detailId != null) detailId = null else recoveryHistory = false
    }
    Scaffold(
        containerColor = Ink,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (session == null && !editing && detailId == null) {
                NavigationBar(containerColor = Ink, tonalElevation = 0.dp) {
                    NavigationBarItem(selected = tab == "plans", onClick = { tab = "plans" },
                        icon = { Text("◷", fontSize = 24.sp) }, label = { Text("我的方案") })
                    NavigationBarItem(selected = tab == "history", onClick = { tab = "history" },
                        icon = { Text("≡", fontSize = 26.sp) }, label = { Text("历史记录") })
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (storageError != null) {
                Text(storageError.orEmpty(), color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            if (browsingRecovery) TextButton(onClick = { recoveryHistory = false; detailId = null }) {
                Text("‹ 返回待恢复计时")
            }
            when {
                showingTimer -> TimerScreen(session!!, controller) {
                    val id = session!!.id
                    if (session?.status == SessionStatus.RECOVERY) {
                        recoveryHistory = true; tab = "history"; detailId = null
                    } else {
                        controller.dismiss(); tab = "history"; detailId = id
                    }
                }
                editing -> {
                    BackHandler { editing = false }
                    PlanEditor(editingJson?.let(JsonCodec::decodePlan), onCancel = { editing = false },
                        onSave = { plan, startAfter ->
                            val error = controller.savePlan(plan)
                            if (error == null) {
                                editing = false; tab = "plans"
                                if (startAfter) start(plan, plan.rounds) else message("方案已保存")
                            } else message(error)
                        })
                }
                detailId != null -> {
                    BackHandler { detailId = null }
                    val detail = history.firstOrNull { it.id == detailId }
                    if (detail != null) HistoryDetail(detail, onDelete = controller::deleteHistory) { detailId = null }
                    else { detailId = null }
                }
                tab == "history" -> historyState.SaveableStateProvider("history") {
                    HistoryScreen(history, onDelete = controller::deleteHistory) { detailId = it.id }
                }
                else -> PlansScreen(plans,
                    onNew = { editingJson = null; editing = true },
                    onEdit = { editingJson = JsonCodec.encodePlan(it); editing = true },
                    onDelete = { controller.deletePlan(it.id)?.let(::message) },
                    onStart = ::start)
            }
        }
    }
}

@Composable private fun PlansScreen(
    plans: List<TimerPlan>, onNew: () -> Unit, onEdit: (TimerPlan) -> Unit,
    onDelete: (TimerPlan) -> Unit, onStart: (TimerPlan, Int) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("interface", 0) }
    var selectedId by rememberSaveable { mutableStateOf(prefs.getString("selectedPlan", null)) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val deleting = plans.firstOrNull { it.id == deletingId }
    if (deleting != null) AlertDialog(onDismissRequest = { deletingId = null },
        title = { Text("删除「${deleting.name}」？") },
        text = { Text("该方案会从列表移除，过去的计时历史仍然保留。") },
        confirmButton = { TextButton(onClick = { onDelete(deleting); deletingId = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deletingId = null }) { Text("取消") } })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("◷", color = Orange, fontSize = 28.sp)
                    Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
                Tag("离线 · 本地保存")
            }
        }
        item { Spacer(Modifier.height(8.dp)); SectionHeading("我的方案", "选好节奏，把注意力留给运动。") }
        item {
            FilledTonalButton(onClick = onNew, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Text("＋  创建循环方案", fontWeight = FontWeight.SemiBold)
            }
        }
        if (plans.isEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("从你的第一个循环开始", style = MaterialTheme.typography.titleLarge)
                    Text("为事件命名，设好时长与轮数。保存一次，以后直接开始。", color = Muted)
                }
            }
        }
        items(plans, key = { it.id }) { plan ->
            val selected = selectedId == plan.id
            var rounds by rememberSaveable(plan.id, plan.rounds) { mutableStateOf(plan.rounds.toString()) }
            LaunchedEffect(selected) { if (!selected) rounds = plan.rounds.toString() }
            val actualRounds = rounds.toIntOrNull()
            val valid = actualRounds != null && actualRounds in 1..999
            Card(modifier = Modifier.fillMaxWidth().clickable {
                selectedId = if (selected) null else plan.id
                prefs.edit { putString("selectedPlan", selectedId) }
            }, colors = CardDefaults.cardColors(containerColor = Panel),
                border = BorderStroke(1.dp, if (selected) Orange.copy(alpha = .7f) else Panel), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Text(plan.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(plan.events.joinToString("  →  ") { it.name }, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Tag("${plan.events.size} 个事件"); Tag("默认 ${plan.rounds} 轮")
                        Tag(formatClock(RunConfig(plan).totalEventMs), accent = true)
                    }
                    if (!selected) Text("点选开始  ›", color = Orange, fontSize = 13.sp)
                    if (selected) {
                        HorizontalDivider(color = Muted.copy(alpha = .15f))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            OutlinedTextField(rounds, onValueChange = { if (it.length <= 4) rounds = it },
                                label = { Text("本次循环次数") }, supportingText = { Text("仅本次生效") },
                                isError = !valid, singleLine = true, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("预计用时", color = Muted, fontSize = 12.sp)
                                Text(if (valid) formatDuration(RunConfig(plan, actualRounds).totalDurationMs) else "—", fontWeight = FontWeight.SemiBold)
                                Text("含准备 ${plan.preparationSeconds} 秒", fontSize = 11.sp, color = Muted)
                            }
                        }
                        Button(onClick = { onStart(plan, actualRounds!!) }, enabled = valid,
                            modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                            Text("开始计时", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { onEdit(plan) }) { Text("编辑方案") }
                            TextButton(onClick = { deletingId = plan.id }) { Text("删除", color = Muted) }
                        }
                    }
                }
            }
        }
        item {
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    .onFailure { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:${context.packageName}".toUri())) }
            }, modifier = Modifier.fillMaxWidth()) { Text("后台计时设置", color = Muted, fontSize = 13.sp) }
            Text("长时间锁屏使用时，可在系统设置中允许 ${stringResource(R.string.app_name)} 持续后台运行。", color = Muted,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}
