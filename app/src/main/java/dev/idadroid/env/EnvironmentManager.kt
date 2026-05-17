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
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

class EnvironmentManager(context: Context) {
    private val appContext = context.applicationContext
    private val paths = EnvironmentPaths.of(appContext)
    private val metadataStore = MetadataStore(paths)
    private val importer = RootfsImporter(appContext, paths)
    private val validator = RootfsValidator(appContext, paths)
    private val prootInstaller = ProotBinaryInstaller(appContext, paths)

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<EnvironmentState> = _state.asStateFlow()

    fun refresh() {
        _state.value = loadState()
    }

    fun importRootfs(uri: Uri): Flow<ImportProgress> = importer.importFromUri(uri)
        .onEach { progress ->
            if (progress.stage == ImportStage.Done || progress.stage == ImportStage.Error) refresh()
        }
        .onCompletion { refresh() }

    suspend fun revalidate(): ValidationReport = withContext(Dispatchers.IO) {
        prootInstaller.ensureInstalled()
        val report = validator.validate()
        val old = metadataStore.load() ?: EnvironmentMetadata(envId = paths.envId)
        metadataStore.save(old.copy(validation = report))
        if (report.ok) paths.readyMarker.writeText("validatedAt=${java.time.Instant.now()}\n")
        refresh()
        report
    }

    suspend fun deleteEnvironment() = withContext(Dispatchers.IO) {
        paths.envDir.deleteRecursively()
        refresh()
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
