package com.jianshen.clock

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import androidx.core.app.NotificationCompat
import com.jianshen.clock.core.*

class TimerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var controller: ClockController
    private lateinit var notifications: NotificationManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private var tone: ToneGenerator? = null
    private val transitionAlerts = TransitionAlerts()
    private var ownedSessionId: String? = null
    private var lastNotificationText = ""
    private var wakeRenewedAt = 0L
    private var finishing = false
    private lateinit var alarms: AlarmManager
    private var alarmDeadline = -1L
    private var alarmWallDeadline = -1L

    override fun onCreate() {
        super.onCreate()
        controller = ClockController.get(this)
        alarms = getSystemService(AlarmManager::class.java)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.timer_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = "显示当前事件和轮次，并提供暂停与继续操作"
            setSound(null, null); enableVibration(false)
        })
        notifications.createNotificationChannel(NotificationChannel(RESULT_CHANNEL, getString(R.string.result_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null); enableVibration(false)
        })
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JianshenClock:timer").apply {
            setReferenceCounted(false)
        }
        tone = try { ToneGenerator(AudioManager.STREAM_ALARM, 75) } catch (_: RuntimeException) { null }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = controller.session.value
        if (current == null || current.isTerminal || current.status == SessionStatus.RECOVERY) {
            stopSelf(); return START_NOT_STICKY
        }
        ownedSessionId = current.id
        finishing = false
        try {
            val notification = notification(current)
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (error: Exception) {
            controller.serviceFailed(error); stopSelf(); return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PAUSE -> controller.pause()
            ACTION_RESUME -> controller.resume()
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_NOT_STICKY
    }

    private val ticker = object : Runnable {
        override fun run() {
            controller.tick()
            val current = controller.session.value
            if (current == null) { stopSelf(); return }
            if (current.isTerminal) {
                releaseWakeLock()
                cancelBoundary()
                if (current.status == SessionStatus.COMPLETED && !finishing) {
                    finishing = true
                    signal(current, complete = true)
                    showResult(current)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    handler.postDelayed({ if (controller.session.value?.isTerminal != false) stopSelf() }, 900)
                } else if (current.status != SessionStatus.COMPLETED) stopSelf()
                return
            }
            if (current.status == SessionStatus.ACTIVE) {
                maintainWakeLock()
                scheduleBoundary(current)
                transitionAlerts.signalIfNeeded(current) { signal(current, complete = false) }
            } else { releaseWakeLock(); cancelBoundary() }
            val text = notificationText(current)
            if (text != lastNotificationText) {
                lastNotificationText = text
                try { notifications.notify(NOTIFICATION_ID, notification(current)) } catch (_: SecurityException) { }
            }
            handler.postDelayed(this, if (current.status == SessionStatus.ACTIVE) 100 else 500)
        }
    }

    private fun maintainWakeLock() {
        val now = SystemClock.elapsedRealtime()
        if (!wakeLock.isHeld || now - wakeRenewedAt >= 60_000) {
            wakeLock.acquire(120_000L)
            wakeRenewedAt = now
        }
    }

    private fun releaseWakeLock() { if (wakeLock.isHeld) wakeLock.release() }

    private fun boundaryIntent() = PendingIntent.getForegroundService(this, 3,
        Intent(this, TimerService::class.java).setAction("com.jianshen.clock.BOUNDARY"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** A visible timer alarm wakes the service at the next transition even in Doze. */
    private fun scheduleBoundary(value: TimerSession) {
        if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) return
        val remaining = TimerEngine.progress(value).phaseRemainingMs.coerceAtLeast(1)
        val deadline = value.lastTickElapsedMs + remaining
        val wallDeadline = System.currentTimeMillis() + remaining
        if (deadline == alarmDeadline && kotlin.math.abs(wallDeadline - alarmWallDeadline) < 1000) return
        try {
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(wallDeadline, openApp()), boundaryIntent())
            alarmDeadline = deadline; alarmWallDeadline = wallDeadline
        } catch (_: SecurityException) { /* Keep the foreground timer usable if special access changes. */ }
    }

    private fun cancelBoundary() {
        if (alarmDeadline != -1L) alarms.cancel(boundaryIntent())
        alarmDeadline = -1L; alarmWallDeadline = -1L
    }

    private fun signal(session: TimerSession, complete: Boolean) {
        if (session.config.plan.soundEnabled) {
            tone?.startTone(if (complete) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_ACK,
                if (complete) 600 else 160)
        }
        if (session.config.plan.vibrationEnabled) {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
                else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
            if (complete) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 100, 180, 100, 260), -1))
            else vibrator.vibrate(VibrationEffect.createOneShot(140, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun notificationText(value: TimerSession): String {
        val p = TimerEngine.progress(value)
        val state = if (value.status == SessionStatus.PAUSED) "已暂停 · " else ""
        return "$state${p.title} ${formatClock(p.phaseRemainingMs)} · 第 ${p.round}/${value.config.rounds} 轮"
    }

    private fun notification(value: TimerSession): Notification {
        val paused = value.status == SessionStatus.PAUSED
        val action = if (paused) ACTION_RESUME else ACTION_PAUSE
        val toggle = PendingIntent.getForegroundService(this, 2, Intent(this, TimerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(value.config.plan.name)
            .setContentText(notificationText(value)).setContentIntent(openApp())
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .addAction(0, if (paused) "继续" else "暂停", toggle)
            .build()
    }

    private fun showResult(value: TimerSession) {
        try {
            notifications.notify(RESULT_ID, NotificationCompat.Builder(this, RESULT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("${value.config.plan.name} · 全部完成")
                .setContentText("完成 ${value.config.rounds} 轮 · 计时 ${formatDuration(value.elapsedEventMs)}")
                .setContentIntent(openApp()).setAutoCancel(true).setOnlyAlertOnce(true).setSilent(true).build())
        } catch (_: SecurityException) { }
    }

    private fun openApp() = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    override fun onTaskRemoved(rootIntent: Intent?) { controller.checkpoint(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cancelBoundary()
        releaseWakeLock()
        tone?.release(); tone = null
        controller.serviceStoppedUnexpectedly(ownedSessionId)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null

    companion object {
        private const val CHANNEL = "running_timer"
        private const val RESULT_CHANNEL = "timer_result"
        private const val NOTIFICATION_ID = 11
        private const val RESULT_ID = 12
        private const val ACTION_PAUSE = "com.jianshen.clock.PAUSE"
        private const val ACTION_RESUME = "com.jianshen.clock.RESUME"
    }
}
