package dev.idadroid.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.idadroid.R
import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.proot.IdaProotRuntime
import dev.idadroid.proot.ProotBinaryInstaller
import java.io.File

class ProotTerminalActivity : ComponentActivity() {
    private var terminalSession: TerminalSession? = null
    private lateinit var terminalView: TerminalView
    private lateinit var paths: EnvironmentPaths
    private lateinit var logStore: TerminalLogStore
    private lateinit var statusText: TextView

    private var ctrlPressed = false
    private var altPressed = false
    private var ctrlButton: TextView? = null
    private var altButton: TextView? = null
    private var textSizePx = 40
    private var launchSpec: IdaProotRuntime.TerminalLaunchSpec? = null
    private var initialCommandSent = false

    private val terminalViewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale > 1.06f) changeTextSize(2) else if (scale < 0.94f) changeTextSize(-2)
            return 1.0f
        }

        override fun onSingleTapUp(e: MotionEvent?) {
            showSoftKeyboard()
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) = Unit

        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
            if (keyCode == KeyEvent.KEYCODE_ENTER && session != null && !session.isRunning) {
                finish()
                return true
            }
            return false
        }

        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = false

        override fun readControlKey(): Boolean {
            val value = ctrlPressed
            ctrlPressed = false
            updateModifierButtons()
            return value
        }

        override fun readAltKey(): Boolean {
            val value = altPressed
            altPressed = false
            updateModifierButtons()
            return value
        }

        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun onEmulatorSet() = Unit
        override fun logError(tag: String?, message: String?) = appendLog("TerminalView ERROR ${tag.orEmpty()}: ${message.orEmpty()}")
        override fun logWarn(tag: String?, message: String?) = appendLog("TerminalView WARN ${tag.orEmpty()}: ${message.orEmpty()}")
        override fun logInfo(tag: String?, message: String?) = appendLog("TerminalView INFO ${tag.orEmpty()}: ${message.orEmpty()}")
        override fun logDebug(tag: String?, message: String?) = appendLog("TerminalView DEBUG ${tag.orEmpty()}: ${message.orEmpty()}")
        override fun logVerbose(tag: String?, message: String?) = appendLog("TerminalView VERBOSE ${tag.orEmpty()}: ${message.orEmpty()}")
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) =
            appendLog("TerminalView EXCEPTION ${tag.orEmpty()}: ${message.orEmpty()} ${e?.stackTraceToString().orEmpty()}")
        override fun logStackTrace(tag: String?, e: Exception?) =
            appendLog("TerminalView EXCEPTION ${tag.orEmpty()}: ${e?.stackTraceToString().orEmpty()}")
    }

    private val terminalSessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            if (::terminalView.isInitialized) terminalView.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            updateStatus()
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            appendLog("session finished exit=${finishedSession.exitStatus}")
            updateStatus()
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("IDAdroid terminal selection", text.orEmpty()))
            toast("已复制")
            appendLog("copied ${text.orEmpty().length} chars")
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            pasteFromClipboard()
        }

        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
            appendLog("session pid=$pid")
            updateStatus()
            sendInitialCommandIfNeeded()
        }
        override fun getTerminalCursorStyle(): Int = 0
        override fun logError(tag: String?, message: String?) = appendLog("TerminalSession ERROR ${tag.orEmpty()}: ${message.orEmpty()}")
        override fun logWarn(tag: String?, message: String?) = appendLog("TerminalSession WARN ${tag.orEmpty()}: ${message.orEmpty()}")
        override fun logInfo(tag: String?, message: String?) = appendLog("TerminalSession INFO ${tag.orEmpty()}: ${message.orEmpty()}")
        override fun logDebug(tag: String?, message: String?) = appendLog("TerminalSession DEBUG ${tag.orEmpty()}: ${message.orEmpty()}")
        override fun logVerbose(tag: String?, message: String?) = appendLog("TerminalSession VERBOSE ${tag.orEmpty()}: ${message.orEmpty()}")
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) =
            appendLog("TerminalSession EXCEPTION ${tag.orEmpty()}: ${message.orEmpty()} ${e?.stackTraceToString().orEmpty()}")
        override fun logStackTrace(tag: String?, e: Exception?) =
            appendLog("TerminalSession EXCEPTION ${tag.orEmpty()}: ${e?.stackTraceToString().orEmpty()}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        paths = EnvironmentPaths.of(applicationContext)
        logStore = TerminalLogStore(applicationContext, paths)
        if (!prepareRuntime()) return
        bindLayout()
        startNewSession()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::paths.isInitialized || !paths.rootfsDir.isDirectory || !paths.prootBinary.isFile) return
        bindLayout()
        attachSessionToTerminalView()
    }

    override fun onDestroy() {
        terminalSession?.finishIfRunning()
        terminalSession = null
        super.onDestroy()
    }

    private fun prepareRuntime(): Boolean {
        if (!paths.rootfsDir.isDirectory) {
            showUnavailableView("rootfs 目录不存在", "请先返回首页导入 rootfs。")
            return false
        }
        val installResult = runCatching { ProotBinaryInstaller(applicationContext, paths).ensureInstalled() }
        installResult
            .onSuccess { appendLog("proot ready path=${it.binary.absolutePath} asset=${it.assetName} copied=${it.copied}") }
            .onFailure { error ->
                appendLog("proot install failed: ${error.stackTraceToString()}")
                showUnavailableView("proot binary 不可用", error.message ?: error::class.java.simpleName)
                return false
            }
        return true
    }

    private fun bindLayout() {
        setContentView(R.layout.terminal_activity_layout)
        terminalView = findViewById(R.id.terminal_view)
        statusText = findViewById(R.id.terminal_status_text)

        setupToolbar()

        terminalView.setBackgroundColor(Color.BLACK)
        terminalView.setTextSize(textSizePx)
        terminalView.keepScreenOn = true
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        terminalView.setTerminalViewClient(terminalViewClient)

        setupExtraKeys()
        updateStatus()
    }

    private fun setupToolbar() {
        styleToolbarButton(findViewById(R.id.terminal_close_button)) { finish() }
        styleToolbarButton(findViewById(R.id.terminal_paste_button)) { pasteFromClipboard() }
        styleToolbarButton(findViewById(R.id.terminal_text_smaller_button)) { changeTextSize(-2) }
        styleToolbarButton(findViewById(R.id.terminal_text_larger_button)) { changeTextSize(2) }
        styleToolbarButton(findViewById(R.id.terminal_log_button)) { showLogView() }
        styleToolbarButton(findViewById(R.id.terminal_reconnect_button)) { restartSession() }
    }

    private fun attachSessionToTerminalView() {
        terminalSession?.let {
            it.updateTerminalSessionClient(terminalSessionClient)
            terminalView.attachSession(it)
            terminalView.onScreenUpdated()
        }
        terminalView.requestFocus()
        updateModifierButtons()
        updateStatus()
    }

    private fun startNewSession() {
        runCatching { createTerminalSession() }
            .onSuccess { session ->
                terminalSession = session
                attachSessionToTerminalView()
                appendLog("launch\n${IdaProotRuntime(applicationContext, paths).describe(requireNotNull(launchSpec))}")
                sendInitialCommandIfNeeded(delayMs = 500)
            }
            .onFailure { error ->
                appendLog("terminal launch failed: ${error.stackTraceToString()}")
                showUnavailableView("终端启动失败", error.message ?: error::class.java.simpleName)
            }
    }

    private fun restartSession() {
        appendLog("restart requested")
        terminalSession?.finishIfRunning()
        terminalSession = null
        launchSpec = null
        initialCommandSent = false
        startNewSession()
    }

    private fun createTerminalSession(): TerminalSession {
        val cwd = if (File(paths.rootfsDir, "root/pi_workspace").isDirectory) IdaProotRuntime.DEFAULT_WORKSPACE else "/root"
        val spec = IdaProotRuntime(applicationContext, paths).interactiveShellSpec(cwd = cwd)
        launchSpec = spec
        return TerminalSession(
            spec.executable,
            spec.workingDirectory,
            spec.args,
            spec.environment,
            4000,
            terminalSessionClient
        )
    }

    private fun sendInitialCommandIfNeeded(delayMs: Long = 0) {
        val command = intent.getStringExtra(EXTRA_STARTUP_COMMAND)?.trim().orEmpty()
        if (command.isBlank() || initialCommandSent) return
        val writer = Runnable {
            val session = terminalSession
            if (!initialCommandSent && session != null && session.pid > 0 && session.isRunning) {
                initialCommandSent = true
                session.write(command)
                session.write("\n")
                appendLog("startup command sent: $command")
            }
        }
        if (delayMs > 0 && ::terminalView.isInitialized) terminalView.postDelayed(writer, delayMs) else writer.run()
    }

    private fun setupExtraKeys() {
        val container = findViewById<LinearLayout>(R.id.extra_keys_container)
        container.removeAllViews()
        ctrlButton = null
        altButton = null

        val row1 = listOf(
            ExtraKeyDef("ESC", "\u001b"),
            ExtraKeyDef("/", "/"),
            ExtraKeyDef("—", "-"),
            ExtraKeyDef("HOME", "\u001b[H"),
            ExtraKeyDef("↑", "\u001b[A"),
            ExtraKeyDef("END", "\u001b[F"),
            ExtraKeyDef("PGUP", "\u001b[5~"),
        )
        val row2 = listOf(
            ExtraKeyDef("⇥", "\t"),
            ExtraKeyDef("CTRL", isCtrl = true),
            ExtraKeyDef("ALT", isAlt = true),
            ExtraKeyDef("←", "\u001b[D"),
            ExtraKeyDef("↓", "\u001b[B"),
            ExtraKeyDef("→", "\u001b[C"),
            ExtraKeyDef("PGDN", "\u001b[6~"),
        )

        container.addView(createKeyRow(row1))
        container.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
        })
        container.addView(createKeyRow(row2))
    }

    private fun createKeyRow(keys: List<ExtraKeyDef>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        keys.forEach { key ->
            val button = keyButton(key.label) { handleExtraKey(key) }
            button.layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                marginStart = dp(1)
                marginEnd = dp(1)
            }
            if (key.isCtrl) ctrlButton = button
            if (key.isAlt) altButton = button
            addView(button)
        }
    }

    private fun handleExtraKey(key: ExtraKeyDef) {
        when {
            key.isCtrl -> ctrlPressed = !ctrlPressed
            key.isAlt -> altPressed = !altPressed
            else -> terminalSession?.write(key.sequence)
        }
        updateModifierButtons()
        terminalView.requestFocus()
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this).toString()
            terminalSession?.write(text)
            appendLog("pasted ${text.length} chars")
        } else {
            toast("剪贴板为空")
        }
        terminalView.requestFocus()
    }

    private fun showSoftKeyboard() {
        terminalView.requestFocusFromTouch()
        terminalView.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.restartInput(terminalView)
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun changeTextSize(deltaPx: Int) {
        textSizePx = (textSizePx + deltaPx).coerceIn(24, 72)
        if (::terminalView.isInitialized) terminalView.setTextSize(textSizePx)
        updateStatus()
    }

    private fun updateStatus() {
        if (!::statusText.isInitialized) return
        val session = terminalSession
        val cwd = launchSpec?.args?.let { args ->
            val index = args.indexOf("-w")
            if (index >= 0) args.getOrNull(index + 1) else null
        } ?: IdaProotRuntime.DEFAULT_WORKSPACE
        val state = when {
            session == null -> "未启动"
            session.isRunning -> "运行中 pid=${session.pid.takeIf { it > 0 } ?: "…"}"
            else -> "已退出 exit=${session.exitStatus}"
        }
        statusText.text = "$state · $cwd · ${textSizePx}px"
    }

    private fun showLogView() {
        val logText = logStore.readTail().ifBlank { "暂无终端日志。\n日志路径：${logStore.absolutePath()}" }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101014.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(toolbarButton("返回") {
            bindLayout()
            attachSessionToTerminalView()
        }, LinearLayout.LayoutParams(dp(64), dp(36)))
        toolbar.addView(toolbarButton("复制") {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("IDAdroid terminal log", logStore.readTail()))
            toast("日志已复制")
        }, LinearLayout.LayoutParams(dp(64), dp(36)).apply { leftMargin = dp(8) })
        toolbar.addView(toolbarButton("清空") {
            logStore.clear()
            showLogView()
        }, LinearLayout.LayoutParams(dp(64), dp(36)).apply { leftMargin = dp(8) })
        root.addView(toolbar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val text = TextView(this).apply {
            setTextColor(0xFFECE6F0.toInt())
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextIsSelectable(true)
            text = logText
        }
        root.addView(ScrollView(this).apply { addView(text) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })
        setContentView(root)
    }

    private fun showUnavailableView(title: String, details: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "$title\n\n$details"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "返回"
            setTextColor(0xFF5C6BC0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = "查看日志"
            setTextColor(0xFF5C6BC0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            setOnClickListener { showLogView() }
        })
        setContentView(root)
    }

    private fun toolbarButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFFECE6F0.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = keyBackground(active = false, radiusDp = 8)
        setOnClickListener { onClick() }
    }

    private fun styleToolbarButton(button: TextView, onClick: () -> Unit) {
        button.typeface = Typeface.DEFAULT_BOLD
        button.gravity = Gravity.CENTER
        button.background = keyBackground(active = false, radiusDp = 8)
        button.setOnClickListener { onClick() }
    }

    private fun keyButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 11f else 12f)
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
        background = keyBackground(active = false, radiusDp = 4)
        setOnClickListener { onClick() }
    }

    private fun keyBackground(active: Boolean, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(if (active) 0xFF5C6BC0.toInt() else 0xFF424242.toInt())
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun updateModifierButtons() {
        ctrlButton?.background = keyBackground(ctrlPressed, radiusDp = 4)
        altButton?.background = keyBackground(altPressed, radiusDp = 4)
    }

    private fun appendLog(message: String) {
        if (::logStore.isInitialized) logStore.append(message)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private data class ExtraKeyDef(
        val label: String,
        val sequence: String = "",
        val isCtrl: Boolean = false,
        val isAlt: Boolean = false
    )

    companion object {
        const val EXTRA_STARTUP_COMMAND = "dev.idadroid.terminal.STARTUP_COMMAND"
    }
}
