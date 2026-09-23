package dev.idadroid

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.idadroid.service.FloatingWindowService
import dev.idadroid.service.KeepAliveService
import dev.idadroid.settings.IdaDroidSettings
import dev.idadroid.ui.IdaDroidApp
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SCREEN = "idadroid.screen"
        const val SCREEN_AGENT = "agent"
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestFileAccessIfNeeded()
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startKeepAliveAndCheckBattery()
    }

    /** Monotonic counter bumped whenever an intent asks to open the Agent screen. */
    private val agentRequest = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getStringExtra(EXTRA_SCREEN) == SCREEN_AGENT) agentRequest.value += 1
        val initialAgent = intent?.getStringExtra(EXTRA_SCREEN) == SCREEN_AGENT
        setContent { IdaDroidApp(initialAgent = initialAgent, agentRequest = agentRequest) }
        checkStartupPermissions()
        restoreFloatingWindowIfEnabled()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // When the task already holds MainActivity the launcher intent resumes
        // this instance via onNewIntent; route the screen request into the flow
        // so the composable can react instead of staying on the current screen.
        if (intent.getStringExtra(EXTRA_SCREEN) == SCREEN_AGENT) agentRequest.value += 1
    }

    private fun restoreFloatingWindowIfEnabled() {
        // After an update/reboot the service is no longer running while the setting still
        // says enabled; bring the bubble back automatically.
        val settings = IdaDroidSettings(this)
        if (settings.floatingWindowEnabled.value && Settings.canDrawOverlays(this)) {
            runCatching { FloatingWindowService.start(this) }
        }
    }

    private fun checkStartupPermissions() {
        KeepAliveService.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestFileAccessIfNeeded()
        }
    }

    private fun requestFileAccessIfNeeded() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager() -> {
                openManageAllFilesAccessSettings()
                startKeepAliveAndCheckBattery()
            }
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> {
                val permissions = buildList {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    ) {
                        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }.toTypedArray()
                if (permissions.isNotEmpty()) storagePermissionLauncher.launch(permissions) else startKeepAliveAndCheckBattery()
            }
            else -> startKeepAliveAndCheckBattery()
        }
    }

    private fun openManageAllFilesAccessSettings() {
        val packageUri = "package:$packageName".toUri()
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = packageUri },
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = packageUri }
        )
        val intent = intents.firstOrNull { it.resolveActivity(packageManager) != null } ?: return
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Some OEM builds hide all-files settings; SAF import still works as fallback.
        }
    }

    private fun startKeepAliveAndCheckBattery() {
        runCatching { KeepAliveService.start(this) }
        maybeRequestIgnoreBatteryOptimizations()
    }

    private fun maybeRequestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        val prefs = getSharedPreferences("startup-permissions", MODE_PRIVATE)
        if (prefs.getBoolean("battery_optimization_requested", false)) return
        prefs.edit().putBoolean("battery_optimization_requested", true).apply()

        val packageUri = "package:$packageName".toUri()
        val intents = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = packageUri },
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = packageUri }
        )
        val intent = intents.firstOrNull { it.resolveActivity(packageManager) != null } ?: return
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Some OEM builds hide the battery optimization settings. Keep the app usable.
        }
    }
}
