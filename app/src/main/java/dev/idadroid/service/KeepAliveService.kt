package dev.idadroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.idadroid.R
import dev.idadroid.IdaDroidApplication
import dev.idadroid.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class KeepAliveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private val agent get() = (application as IdaDroidApplication).agentManager

    private data class AgentNotification(val sessionId: String?, val phase: String?, val busy: Boolean, val failed: Boolean)

    private fun notificationState(): AgentNotification = agent.state.value.let {
        AgentNotification(it.activeSessionId, it.processingPhase, it.isWorking || it.status == "starting", it.error != null)
    }

    companion object {
        const val CHANNEL_ID = "idadroid_keep_alive"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_ABORT = "dev.idadroid.ABORT_AGENT"
        private const val EXTRA_SESSION = "sessionId"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.keep_alive_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.keep_alive_channel_description)
                    setShowBadge(false)
                }
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
        }

        fun start(context: Context) {
            ensureChannel(context)
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(notificationState())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (intent?.action == ACTION_ABORT) intent.getStringExtra(EXTRA_SESSION)?.let { agent.abort(it) }
        if (stateJob == null) {
            stateJob = scope.launch {
                agent.state.map {
                    AgentNotification(it.activeSessionId, it.processingPhase, it.isWorking || it.status == "starting", it.error != null)
                }.distinctUntilChanged().collect { state ->
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
        return START_STICKY
    }

    private fun buildNotification(state: AgentNotification): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (state.sessionId != null) putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_AGENT)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val text = when {
            state.busy -> when (state.phase) {
                "connecting" -> "Agent 正在连接，点击查看进度"
                "receiving" -> "Agent 正在回复"
                "executing_tool" -> "Agent 正在执行工具"
                "compacting" -> "Agent 正在压缩上下文"
                else -> "Agent 任务进行中"
            }
            state.failed -> "Agent 需要处理，点击查看错误"
            else -> getString(R.string.keep_alive_notification_text)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.keep_alive_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_idadroid)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        if (state.busy && state.sessionId != null) {
            val stop = PendingIntent.getService(this, 1,
                Intent(this, KeepAliveService::class.java).setAction(ACTION_ABORT).putExtra(EXTRA_SESSION, state.sessionId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(android.R.drawable.ic_media_pause, "停止 Agent", stop)
        }
        return builder.build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
