package dev.idadroid

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.idadroid.agent.PiAgentManager
import dev.idadroid.agent.PiUpdateManager
import dev.idadroid.settings.IdaDroidSettings
import dev.idadroid.terminal.ProotTerminalActivity
import dev.idadroid.vnc.VncSessionManager
import java.util.Collections
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Recreated activities share the PI process and update owners with the application. */
class IdaDroidApplication : Application() {
    val agentManager: PiAgentManager by lazy { PiAgentManager(this) }
    val piUpdateManager: PiUpdateManager by lazy { PiUpdateManager(this) }

    private val settingsStore by lazy { IdaDroidSettings(this) }
    private val vncManager by lazy { VncSessionManager(this, settingsStore) }
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var guiStopJob: Job? = null
    private val terminalActivities = Collections.synchronizedSet(
        LinkedHashSet<ProotTerminalActivity>()
    )

    private val activityCallbacks = object : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is ProotTerminalActivity) terminalActivities.add(activity)
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (activity is ProotTerminalActivity) terminalActivities.remove(activity)
            // Activity destruction is not sufficient on its own: rotation sets
            // isChangingConfigurations and must leave the GUI untouched. A real
            // finishing MainActivity is the task-exit path.
            if (activity is MainActivity && activity.isFinishing && !activity.isChangingConfigurations) {
                requestGuiStopOnAppExit()
            }
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(activityCallbacks)
    }

    /**
     * Stops every currently-created terminal before an environment replacement.
     * Activity callbacks run on main, but callers may be on an IO coroutine, so
     * both collection and terminal calls are explicitly serialized on main.
     */
    suspend fun stopTerminalsForEnvironmentChange() {
        withContext(Dispatchers.Main.immediate) {
            val terminals = terminalActivities.toList()
            var firstFailure: Exception? = null
            terminals.forEach { terminal ->
                try {
                    terminal.stopForEnvironmentChange()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    firstFailure?.addSuppressed(error) ?: run { firstFailure = error }
                }
            }
            firstFailure?.let { throw it }
        }
    }

    /** Called by task-removal/finishing paths, never by ordinary Activity stop. */
    fun requestGuiStopOnAppExit() {
        if (!settingsStore.vncSettings.value.stopGuiOnAppExit || guiStopJob?.isActive == true) return
        guiStopJob = lifecycleScope.launch {
            vncManager.stopGui()
        }
    }

}
