package dev.idadroid.env

import dev.idadroid.data.EnvironmentMetadata
import dev.idadroid.util.JsonFormats
import java.io.File
import kotlinx.serialization.encodeToString

class MetadataStore(
    private val paths: EnvironmentPaths
) {
    fun load(): EnvironmentMetadata? = load(paths.metadataFile)

    fun load(file: File): EnvironmentMetadata? = runCatching {
        if (!file.isFile) return null
        JsonFormats.pretty.decodeFromString<EnvironmentMetadata>(file.readText())
    }.getOrNull()

    fun save(metadata: EnvironmentMetadata, file: File = paths.metadataFile) {
        file.parentFile?.mkdirs()
        file.writeText(JsonFormats.pretty.encodeToString(metadata))
    }
}
