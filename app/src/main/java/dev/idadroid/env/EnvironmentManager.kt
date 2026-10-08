package dev.idadroid.env

import android.content.Context
import android.net.Uri
import dev.idadroid.data.EnvironmentMetadata
import dev.idadroid.data.ValidationReport
import dev.idadroid.proot.ProotBinaryInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext

class EnvironmentManager(context: Context) {
    private val appContext = context.applicationContext
    private val paths = EnvironmentPaths.of(appContext)
    private val metadataStore = MetadataStore(paths)
    private val importer = RootfsImporter(appContext, paths)
    private val validator = RootfsValidator(appContext, paths)
    private val prootInstaller = ProotBinaryInstaller(appContext, paths)
    private val configurator = RootfsConfigurator(appContext)

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<EnvironmentState> = _state.asStateFlow()

    fun refresh() {
        _state.update { loadState() }
    }

    fun importRootfs(uri: Uri): Flow<ImportProgress> = importer.importFromUri(uri)
        // 包括取消在内，每次导入只在结束时重新读取一次环境状态。
        .onCompletion { refresh() }

    suspend fun revalidate(): ValidationReport = withContext(Dispatchers.IO) {
        prootInstaller.ensureInstalled()
        if (paths.rootfsDir.isDirectory) configurator.ensureResolverConfig(paths.rootfsDir)
        val report = validator.validate()
        val old = metadataStore.load() ?: EnvironmentMetadata(envId = paths.envId)
        metadataStore.save(old.copy(validation = report))
        if (report.ok) paths.readyMarker.writeText("validatedAt=${java.time.Instant.now()}\n")
        refresh()
        report
    }

    suspend fun deleteEnvironment() = withContext(Dispatchers.IO) {
        try {
            check(paths.envDir.deleteRecursively()) { "无法完整删除 active 环境，请重试" }
        } finally {
            refresh()
        }
    }

    private fun loadState(): EnvironmentState {
        val metadata = metadataStore.load()
        val ready = paths.readyMarker.isFile && paths.rootfsDir.isDirectory && metadata?.validation?.ok == true
        return when {
            ready -> EnvironmentState.Ready(metadata)
            paths.rootfsDir.isDirectory && metadata != null -> EnvironmentState.NotReady(metadata, "环境存在，但验证未通过或 ready marker 缺失")
            else -> EnvironmentState.NoEnvironment
        }
    }
}

sealed interface EnvironmentState {
    val metadata: EnvironmentMetadata?
    val isReady: Boolean

    data object NoEnvironment : EnvironmentState {
        override val metadata: EnvironmentMetadata? = null
        override val isReady: Boolean = false
    }

    data class NotReady(
        override val metadata: EnvironmentMetadata?,
        val reason: String
    ) : EnvironmentState {
        override val isReady: Boolean = false
    }

    data class Ready(
        override val metadata: EnvironmentMetadata
    ) : EnvironmentState {
        override val isReady: Boolean = true
    }
}
