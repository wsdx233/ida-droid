package dev.idadroid

import android.app.Application
import dev.idadroid.agent.PiAgentManager
import dev.idadroid.agent.PiUpdateManager

/** Recreated activities share the PI process and update owners with the application. */
class IdaDroidApplication : Application() {
    val agentManager: PiAgentManager by lazy { PiAgentManager(this) }
    val piUpdateManager: PiUpdateManager by lazy { PiUpdateManager(this) }
}
