package dev.idadroid.data

import kotlinx.serialization.Serializable

@Serializable
data class EnvironmentMetadata(
    val schema: Int = 1,
    val envId: String = "default",
    val sourceName: String = "",
    val importedAt: String = "",
    val rootfsStripComponents: Int = 0,
    val rootfsFormat: String = "unknown",
    val validation: ValidationReport = ValidationReport()
)

@Serializable
data class ValidationReport(
    val ok: Boolean = false,
    val arch: String = "",
    val homeWritable: Boolean = false,
    val ida: IdaStatus = IdaStatus(),
    val idaMcp: FileStatus = FileStatus(path = "/root/ida-pro-9.3/ida-mcp"),
    val usageDoc: FileStatus = FileStatus(path = "/root/ida-pro-9.3/IDA_MCP_MCPC_USAGE.md"),
    val pi: CommandStatus = CommandStatus(name = "pi"),
    val node: CommandStatus = CommandStatus(name = "node"),
    val npm: CommandStatus = CommandStatus(name = "npm"),
    val mcpc: CommandStatus = CommandStatus(name = "mcpc"),
    val vnc: VncStatus = VncStatus(),
    val fatal: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val rawOutput: String = ""
)

@Serializable
data class IdaStatus(
    val exists: Boolean = false,
    val path: String = "/root/ida-pro-9.3",
    val binary: String = "",
    val binaryInfo: String = ""
)

@Serializable
data class FileStatus(
    val path: String = "",
    val exists: Boolean = false,
    val executable: Boolean = false
)

@Serializable
data class CommandStatus(
    val name: String = "",
    val exists: Boolean = false,
    val path: String = "",
    val version: String = ""
)

@Serializable
data class VncStatus(
    val mode: String = "missing",
    val server: String = "",
    val xServer: String = "",
    val wm: String = ""
)
