package dev.idadroid.agent

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Undo journal for the four editable originals; callers hold the lock through reads/materialization. */
internal class PiConfigPersistence(
    workspace: File,
    private val lockFile: File = File(workspace, ".pi-config-lock"),
    private val move: (File, File) -> Unit = { source, target ->
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    }
) {
    private val agentDir = File(workspace, ".idadroid/pi-agent")
    val files = listOf(
        File(agentDir, "idadroid-pi-config.json"),
        File(agentDir, "settings.json"),
        File(agentDir, "models.json"),
        File(workspace, ".pi/APPEND_SYSTEM.md")
    )

    data class FileState(val isFile: Boolean, val modified: Long, val size: Long, val identity: Any?)

    fun fileStates(): List<FileState> = files.map { file ->
        try {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            FileState(attributes.isRegularFile, attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS), attributes.size(), attributes.fileKey())
        } catch (_: NoSuchFileException) {
            FileState(false, 0, 0, null)
        }
    }
    private val journal = File(agentDir, ".config-transaction")

    fun <T> locked(block: () -> T): T = synchronized(processLock) {
        check(lockFile.parentFile!!.isDirectory || lockFile.parentFile!!.mkdirs()) { "Cannot create PI lock directory" }
        RandomAccessFile(lockFile, "rw").use { handle ->
            handle.channel.lock().use {
                recover()
                agentDir.listFiles { file -> file.name.startsWith(".config-discarded-") }
                    ?.forEach { it.deleteRecursively() }
                block()
            }
        }
    }

    /** Prepare every payload and undo copy before publishing even the first replacement. */
    fun save(texts: List<String>) {
        require(texts.size == files.size)
        check(agentDir.isDirectory || agentDir.mkdirs()) { "Cannot create PI config directory" }
        check(journal.mkdir()) { "Cannot prepare PI configuration" }
        try {
            val present = files.mapIndexed { index, file ->
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Cannot create PI config directory" }
                check(!file.exists() || file.isFile) { "PI configuration destination is not a file" }
                writeSynced(File(journal, "new-$index"), (texts[index] + "\n").toByteArray(Charsets.UTF_8))
                if (file.exists()) {
                    copySynced(file, File(journal, "old-$index"))
                    true
                } else false
            }
            writeSynced(File(journal, "ready.tmp"), present.joinToString("\n").toByteArray(Charsets.UTF_8))
            move(File(journal, "ready.tmp"), File(journal, "ready"))
            syncDirectory(journal)
            syncDirectory(agentDir)
            files.forEachIndexed { index, file -> move(File(journal, "new-$index"), file) }
            files.map { it.parentFile!! }.distinct().forEach(::syncDirectory)
            writeSynced(File(journal, "committed.tmp"), byteArrayOf(1))
            move(File(journal, "committed.tmp"), File(journal, "committed"))
        } catch (failure: Throwable) {
            try { recover() } catch (recoveryFailure: Throwable) { failure.addSuppressed(recoveryFailure) }
            throw failure
        }
        // The commit-marker rename is the publication point. Later cleanup cannot turn success
        // into failure (or attempt rollback with a committed marker still visible).
        runCatching {
            syncDirectory(journal)
            discardJournal()
        }
    }

    private fun recover() {
        if (!journal.exists()) return
        val ready = File(journal, "ready")
        if (ready.isFile && !File(journal, "committed").isFile) {
            val present = ready.readLines()
            check(present.size == files.size && present.all { it == "true" || it == "false" }) { "Incomplete PI recovery journal" }
            files.forEachIndexed { index, file ->
                if (present[index] == "true") {
                    val temporary = File(file.parentFile, ".${file.name}.restore")
                    try {
                        copySynced(File(journal, "old-$index"), temporary)
                        move(temporary, file)
                    } finally { temporary.delete() }
                } else {
                    check(!file.exists() || file.delete()) { "Cannot restore missing PI configuration" }
                }
            }
            files.map { it.parentFile!! }.distinct().forEach(::syncDirectory)
        }
        discardJournal()
    }

    private fun discardJournal() {
        // Rename first: interruption while deleting backups must never look like an active undo journal.
        val discarded = File(agentDir, ".config-discarded-${UUID.randomUUID()}")
        move(journal, discarded)
        syncDirectory(agentDir)
        discarded.deleteRecursively()
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        file.createNewFile()
        // Keep the existing private-write permissions for staged user configuration.
        file.setReadable(false, false); file.setWritable(false, false)
        file.setReadable(true, true); file.setWritable(true, true)
        file.outputStream().use { output -> output.write(bytes); output.fd.sync() }
    }

    private fun copySynced(source: File, target: File) {
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        FileChannel.open(target.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    companion object {
        // File locks cover other processes; this prevents overlapping locks across manager instances.
        private val processLock = Any()
    }
}
