package dev.idadroid.env

enum class ImportStage {
    Idle,
    Preflight,
    Extracting,
    Configuring,
    InstallingProot,
    Validating,
    MaterializingWorkspace,
    Activating,
    Done,
    Error
}

data class ImportProgress(
    val stage: ImportStage = ImportStage.Idle,
    val progress: Float? = null,
    val message: String = "",
    val currentFile: String = "",
    val logs: List<String> = emptyList(),
    val error: String? = null
) {
    val isRunning: Boolean
        get() = stage !in setOf(ImportStage.Idle, ImportStage.Done, ImportStage.Error)
}
