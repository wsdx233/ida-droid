package dev.idadroid

import android.app.Application
import dev.idadroid.agent.PiAgentManager

/** The foreground service and recreated activities share one PI process owner. */
class IdaDroidApplication : Application() {
    val agentManager: PiAgentManager by lazy { PiAgentManager(this) }
}
