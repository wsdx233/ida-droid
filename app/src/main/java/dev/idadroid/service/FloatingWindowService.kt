package dev.idadroid.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import dev.idadroid.MainActivity
import dev.idadroid.R
import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.mcp.IdaMcpSessionManager
import dev.idadroid.settings.IdaDroidSettings
import dev.idadroid.terminal.ProotTerminalActivity
import dev.idadroid.vnc.VncSessionManager
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * System overlay (floating bubble + quick panel) for IDAdroid.
 *
 * Shows a draggable bubble above other apps with live status of the
 * imported rootfs environment, IDA GUI/VNC and the pi agent, plus quick
 * actions (open app / terminal / agent chat, start or stop IDA GUI).
 */
class FloatingWindowService : Service() {

    companion object {
        const val CHANNEL_ID = "idadroid_overlay"
        private const val NOTIFICATION_ID = 1002
        private const val TAG = "FloatingWindow"

        const val COLOR_GRAY = "#8A8F98"
        const val COLOR_IDLE = "#3D6DFF"
        const val COLOR_GREEN = "#23A55A"
        const val COLOR_TEAL = "#12B8A6"
        const val COLOR_PANEL = "#F21E2328"
        const val COLOR_BUTTON = "#32383F"
        const val COLOR_PRIMARY = "#3D6DFF"
        const val COLOR_DANGER = "#D95757"

        fun start(context: Context) {
            ensureChannel(context)
            val intent = Intent(context, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.overlay_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.overlay_channel_description)
                    setShowBadge(false)
                }
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var paths: EnvironmentPaths
    private lateinit var settingsStore: IdaDroidSettings
    private lateinit var vncManager: VncSessionManager
    private lateinit var mcpManager: IdaMcpSessionManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var bubble: View? = null
    private var panel: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var panelVisible = false
    private var pollingStarted = false

    private var statusDot: View? = null
    private var statusEnv: TextView? = null
    private var statusIda: TextView? = null
    private var statusAgent: TextView? = null
    private var statusMcp: TextView? = null
    private var toggleIdaButton: TextView? = null
    private var toggleMcpButton: TextView? = null

    private var lastEnvReady = false
    private var lastIdaRunning = false
    private var lastAgentRunning = false
    private var lastMcpRunning = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        paths = EnvironmentPaths.of(this)
        settingsStore = IdaDroidSettings(this)
        vncManager = VncSessionManager(this, settingsStore)
        mcpManager = IdaMcpSessionManager.get(this, settingsStore)
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always call startForeground first: a service started via startForegroundService
        // must do so promptly, otherwise the system kills the process (API 31+).
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_stat_idadroid)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        if (!Settings.canDrawOverlays(this)) {
            // Overlay permission was revoked (e.g. by the system or on update).
            settingsStore.setFloatingWindowEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        settingsStore.setFloatingWindowEnabled(true)

        if (bubble == null) {
            runCatching { addBubble() }
                .onFailure { error ->
                    Log.e(TAG, "addBubble failed", error)
                    settingsStore.setFloatingWindowEnabled(false)
                    stopSelf()
                    return START_NOT_STICKY
                }
        }
        if (!pollingStarted) {
            pollingStarted = true
            scope.launch {
                while (isActive) {
                    runCatching { refreshStatus() }
                    // Poll fast while the panel is visible (status rows are on
                    // screen); slow down when hidden to spare CPU/battery.
                    delay(if (panelVisible) 2_000 else 10_000)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        hidePanel()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        bubbleParams = null
        // Do NOT clear the enabled preference here: onDestroy runs for every
        // stop reason (system kill, memory pressure, forced stop), and clearing
        // it would flip the settings toggle off although the user never disabled
        // the overlay. User-initiated disable paths set the preference first.
        super.onDestroy()
    }

    // ---------- views ----------

    private fun addBubble() {
        val size = dp(52)
        val bubbleView = TextView(this).apply {
            text = "ID"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = ovalBackground(COLOR_IDLE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val metrics = resources.displayMetrics
            x = metrics.widthPixels - size - dp(24)
            y = metrics.heightPixels / 3
        }
        attachDrag(bubbleView, params) {
            if (panelVisible) hidePanel() else showPanel()
        }
        windowManager.addView(bubbleView, params)
        bubble = bubbleView
        bubbleParams = params
    }

    private fun showPanel() {
        if (panel != null) {
            panelVisible = true
            refreshStatusViews()
            return
        }
        val metrics = resources.displayMetrics
        val panelWidth = dp(288)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = roundedBackground(COLOR_PANEL, dp(18))
        }

        // header: status dot + title + collapse
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply {
            background = ovalBackground(COLOR_GRAY)
        }
        header.addView(
            statusDot,
            LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(8)
            }
        )
        header.addView(
            TextView(this).apply {
                text = "IDAdroid 悬浮窗"
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            TextView(this).apply {
                text = "×"
                setTextColor(Color.parseColor("#A6ADBB"))
                textSize = 22f
                gravity = Gravity.CENTER
                setOnClickListener { hidePanel() }
            },
            LinearLayout.LayoutParams(dp(36), dp(36))
        )
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val divider = View(this).apply { setBackgroundColor(Color.parseColor("#3A4048")) }
        root.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(10) })

        // status rows
        statusEnv = statusRow(root, "环境")
        statusIda = statusRow(root, "IDA GUI")
        statusAgent = statusRow(root, "Agent")
        statusMcp = statusRow(root, "MCP HTTP")

        // action buttons
        root.addView(actionButton("打开 IDAdroid", COLOR_BUTTON) { launchMain() }, buttonLp(0))
        root.addView(actionButton("打开终端", COLOR_BUTTON) { launchTerminal() }, buttonLp(0))
        root.addView(actionButton("Agent 聊天", COLOR_BUTTON) { launchAgent() }, buttonLp(0))
        toggleIdaButton = actionButton("启动 IDA", COLOR_PRIMARY) { toggleIda() }
        root.addView(toggleIdaButton!!, buttonLp(0))
        toggleMcpButton = actionButton("启动 MCP", COLOR_PRIMARY) { toggleMcp() }
        root.addView(toggleMcpButton!!, buttonLp(0))
        root.addView(actionButton("关闭悬浮窗", COLOR_DANGER) {
            // User-initiated disable: record intent, then stop the service.
            settingsStore.setFloatingWindowEnabled(false)
            stopSelf()
        }, buttonLp(0))

        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val b = bubbleParams
            x = (b?.x ?: 0)
            y = (b?.y ?: 0) + dp(52) + dp(12)
            if (y + panelHeightHint(metrics.heightPixels) > metrics.heightPixels) {
                y = (b?.y ?: metrics.heightPixels / 3) - dp(52) - dp(12)
            }
            if (y < dp(24)) y = dp(24)
        }
        windowManager.addView(root, params)
        panel = root
        panelParams = params
        panelVisible = true
        refreshStatusViews()
    }

    private fun panelHeightHint(screenH: Int): Int = (screenH * 0.55f).toInt()

    private fun hidePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        panelParams = null
        panelVisible = false
    }

    private fun statusRow(parent: LinearLayout, label: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            TextView(this).apply {
                text = label
                setTextColor(Color.parseColor("#A6ADBB"))
                textSize = 13f
            },
            LinearLayout.LayoutParams(dp(88), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        val value = TextView(this).apply {
            text = "检查中…"
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        row.addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        return value
    }

    private fun actionButton(text: String, color: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = roundedBackground(color, dp(12))
            setOnClickListener { onClick() }
        }

    private fun buttonLp(topMargin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply {
            this.topMargin = dp(10) + topMargin
        }

    // ---------- drag / tap ----------

    private fun attachDrag(view: View, params: WindowManager.LayoutParams, onTap: () -> Unit) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        params.x = startX + dx
                        params.y = startY + dy
                        runCatching { windowManager.updateViewLayout(view, params) }
                        onBubbleMoved()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        snapToEdge(view, params)
                        onBubbleMoved()
                    } else {
                        onTap()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        snapToEdge(view, params)
                        onBubbleMoved()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onBubbleMoved() {
        val b = bubbleParams ?: return
        panelParams?.let { p ->
            p.x = b.x
            p.y = b.y + dp(52) + dp(12)
            panel?.let { runCatching { windowManager.updateViewLayout(it, p) } }
        }
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        val w = view.width.takeIf { it > 0 } ?: dp(52)
        params.x = if (params.x + w / 2 < metrics.widthPixels / 2) dp(8) else metrics.widthPixels - w - dp(8)
        params.y = params.y.coerceIn(dp(8), metrics.heightPixels - w - dp(8))
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    // ---------- status ----------

    private suspend fun refreshStatus() {
        val envReady = runCatching { paths.readyMarker.isFile }.getOrDefault(false)
        val settings = settingsStore.vncSettings.value
        val idaRunning = isTcpOpen(settings.port)
        val agentRunning = isAgentRunning()
        val mcpRunning = isMcpRunning()
        lastEnvReady = envReady
        lastIdaRunning = idaRunning
        lastAgentRunning = agentRunning
        lastMcpRunning = mcpRunning
        mainHandler.post { refreshStatusViews() }
    }

    private fun refreshStatusViews() {
        val bubbleView = bubble as? TextView ?: return
        val (color, label) = when {
            !lastEnvReady -> COLOR_GRAY to "!"
            lastIdaRunning -> COLOR_GREEN to "IDA"
            lastMcpRunning -> COLOR_TEAL to "MCP"
            lastAgentRunning -> COLOR_TEAL to "AI"
            else -> COLOR_IDLE to "ID"
        }
        bubbleView.background = ovalBackground(color)
        bubbleView.text = label

        statusDot?.background = ovalBackground(
            when {
                !lastEnvReady -> COLOR_GRAY
                lastIdaRunning || lastAgentRunning -> COLOR_GREEN
                else -> COLOR_IDLE
            }
        )
        statusEnv?.text = if (lastEnvReady) "已就绪" else "未导入"
        statusIda?.text = if (lastIdaRunning) "运行中" else "已停止"
        statusAgent?.text = if (lastAgentRunning) "运行中" else "已停止"
        statusMcp?.text = if (lastMcpRunning) "运行中" else "已停止"
        toggleIdaButton?.let { btn ->
            btn.text = if (lastIdaRunning) "停止 IDA" else "启动 IDA"
            btn.background = roundedBackground(if (lastIdaRunning) COLOR_DANGER else COLOR_PRIMARY, dp(12))
        }
        toggleMcpButton?.let { btn ->
            btn.text = if (lastMcpRunning) "停止 MCP" else "启动 MCP"
            btn.background = roundedBackground(if (lastMcpRunning) COLOR_DANGER else COLOR_PRIMARY, dp(12))
        }
    }

    private fun toggleIda() {
        scope.launch {
            val settings = settingsStore.vncSettings.value
            val running = isTcpOpen(settings.port)
            val message = if (running) {
                vncManager.stopGui().fold(
                    onSuccess = { "IDA GUI 已停止" },
                    onFailure = { "停止失败：${it.message}" }
                )
            } else {
                vncManager.startGui(openViewer = true).fold(
                    onSuccess = { state -> state.message.ifBlank { "IDA GUI 已启动" } },
                    onFailure = { "启动失败：${it.message}" }
                )
            }
            mainHandler.post { Toast.makeText(this@FloatingWindowService, message, Toast.LENGTH_SHORT).show() }
            refreshStatus()
        }
    }

    private fun toggleMcp() {
        scope.launch {
            val running = isMcpRunning()
            val message = if (running) {
                mcpManager.stop().fold(
                    onSuccess = { "IDA MCP 已停止" },
                    onFailure = { "停止失败：${it.message}" }
                )
            } else {
                // Use the shared manager's settings (same port/host as Home panel).
                mcpManager.start(mcpManager.state.value.settings).fold(
                    onSuccess = { state -> state.message.ifBlank { "IDA MCP 已启动" } },
                    onFailure = { "启动失败：${it.message}" }
                )
            }
            mainHandler.post { Toast.makeText(this@FloatingWindowService, message, Toast.LENGTH_SHORT).show() }
            refreshStatus()
        }
    }

    private fun launchMain() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        intent?.let { startActivity(it) }
    }

    private fun launchTerminal() {
        startActivity(
            Intent(this, ProotTerminalActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun launchAgent() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_AGENT)
        }
        intent?.let { startActivity(it) }
    }

    // ---------- helpers ----------

    private fun isTcpOpen(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 300)
            true
        }
    }.getOrDefault(false)

    private fun isAgentRunning(): Boolean = runCatching {
        val procDir = File("/proc")
        procDir.listFiles { f -> f.name.isNotEmpty() && f.name.all { it.isDigit() } }?.any { pidDir ->
            runCatching {
                val cmdline = String(File(pidDir, "cmdline").readBytes(), Charsets.UTF_8)
                    .split('\u0000')
                    .joinToString(" ")
                cmdline.contains("--mode rpc") && cmdline.contains("proot", ignoreCase = true)
            }.getOrDefault(false)
        } ?: false
    }.getOrDefault(false)

    private fun isMcpRunning(): Boolean {
        // Mirror the session manager's own liveness check: the MCP HTTP port
        // must actually accept connections. /proc cmdline scans are unreliable
        // (the proot wrapper's own cmdline contains the "ida-mcp serve-http"
        // script text) and would report "running" while nothing is listening.
        val port = runCatching { mcpManager.state.value.settings.port }.getOrDefault(8765)
        return isTcpOpen(port)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun ovalBackground(color: String): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(color))
    }

    private fun roundedBackground(color: String, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.parseColor(color))
        cornerRadius = radius.toFloat()
    }
}
