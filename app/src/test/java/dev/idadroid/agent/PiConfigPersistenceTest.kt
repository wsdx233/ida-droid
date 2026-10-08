package dev.idadroid.agent

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PiConfigPersistenceTest {
    private val originals = listOf("old config", "old settings", "old models", "old prompt")
    private val replacements = listOf("new config", "new settings", "new models", "new prompt")

    @Test fun initialReadDoesNotCreateMissingRootfsOrWorkspace() = inWorkspace { hostDirectory ->
        val workspace = File(hostDirectory, "missing-rootfs/workspace")
        val persistence = PiConfigPersistence(workspace, File(hostDirectory, ".host-config-lock"))
        persistence.locked {
            assertTrue(persistence.fileStates().none { it.isFile })
        }
        assertFalse(File(hostDirectory, "missing-rootfs").exists())
    }


    @Test fun successfulSavePublishesAllFourFiles() = inWorkspace { workspace ->
        val persistence = initialized(workspace)
        persistence.locked { persistence.save(replacements) }
        assertEquals(replacements.map { "$it\n" }, persistence.files.map { it.readText() })
        assertFalse(File(workspace, ".idadroid/pi-agent/.config-transaction").exists())
    }

    @Test fun failureAtEveryPublicationStepRestoresAllOriginals() {
        for (failedName in listOf("ready.tmp", "new-0", "new-1", "new-2", "new-3", "committed.tmp")) {
            inWorkspace { workspace ->
                val originalStore = initialized(workspace)
                var failed = false
                val persistence = PiConfigPersistence(workspace) { source, target ->
                    if (!failed && source.name == failedName) {
                        failed = true
                        throw IOException("Injected rename failure")
                    }
                    move(source, target)
                }
                assertTrue(runCatching { persistence.locked { persistence.save(replacements) } }.isFailure)
                assertTrue(failed)
                PiConfigPersistence(workspace).locked {
                    assertEquals(originals, originalStore.files.map { it.readText() })
                }
            }
        }
    }

    @Test fun preparationFailureNeverPublishesEarlierPayloads() = inWorkspace { workspace ->
        val persistence = initialized(workspace)
        assertTrue(persistence.files[2].delete())
        assertTrue(persistence.files[2].mkdir())
        val marker = File(persistence.files[2], "keep")
        marker.writeText("directory content")
        assertTrue(runCatching { persistence.locked { persistence.save(replacements) } }.isFailure)
        assertEquals(originals[0], persistence.files[0].readText())
        assertEquals(originals[1], persistence.files[1].readText())
        assertEquals(originals[3], persistence.files[3].readText())
        assertEquals("directory content", marker.readText())
    }

    @Test fun rollbackRestoresOriginallyMissingFilesAsMissing() = inWorkspace { workspace ->
        val originalStore = initialized(workspace)
        assertTrue(originalStore.files[0].delete())
        assertTrue(originalStore.files[3].delete())
        val persistence = PiConfigPersistence(workspace) { source, target ->
            if (source.name == "committed.tmp") throw IOException("Injected commit failure")
            move(source, target)
        }
        assertTrue(runCatching { persistence.locked { persistence.save(replacements) } }.isFailure)
        assertFalse(originalStore.files[0].exists())
        assertFalse(originalStore.files[3].exists())
        assertEquals(originals[1], originalStore.files[1].readText())
        assertEquals(originals[2], originalStore.files[2].readText())
    }

    @Test fun abandonedPartialCommitIsRecoveredBeforeAnyReadOrNextSave() = inWorkspace { workspace ->
        val originalStore = initialized(workspace)
        val failingStore = PiConfigPersistence(workspace) { source, target ->
            if (source.name == "new-1" || source.name.endsWith(".restore")) {
                throw IOException("Injected publication and rollback failure")
            }
            move(source, target)
        }
        assertTrue(runCatching { failingStore.locked { failingStore.save(replacements) } }.isFailure)
        // The failed recovery retains its complete undo journal rather than exposing mixed files.
        var readInvoked = false
        assertTrue(runCatching { failingStore.locked { readInvoked = true } }.isFailure)
        assertFalse(readInvoked)
        val restartedStore = PiConfigPersistence(workspace)
        restartedStore.locked {
            assertEquals(originals, originalStore.files.map { it.readText() })
            restartedStore.save(replacements)
        }
        assertEquals(replacements.map { "$it\n" }, restartedStore.files.map { it.readText() })
    }

    @Test fun committedJournalIsCleanedWithoutRollingBackSuccessfulSave() = inWorkspace { workspace ->
        val persistence = initialized(workspace)
        var failedCleanup = false
        val failingCleanupStore = PiConfigPersistence(workspace) { source, target ->
            if (source.name == ".config-transaction") {
                failedCleanup = true
                throw IOException("Injected cleanup failure")
            }
            move(source, target)
        }
        failingCleanupStore.locked { failingCleanupStore.save(replacements) }
        assertTrue(failedCleanup)
        PiConfigPersistence(workspace).locked {
            assertEquals(replacements.map { "$it\n" }, persistence.files.map { it.readText() })
        }
        assertFalse(File(workspace, ".idadroid/pi-agent/.config-transaction").exists())
    }

    @Test fun anotherManagerCannotReadDuringPartialPublication() = inWorkspace { workspace ->
        initialized(workspace)
        val firstPublished = CountDownLatch(1)
        val continuePublication = CountDownLatch(1)
        val readerStarted = CountDownLatch(1)
        val readerFinished = CountDownLatch(1)
        val writer = PiConfigPersistence(workspace) { source, target ->
            move(source, target)
            if (source.name == "new-0") {
                firstPublished.countDown()
                check(continuePublication.await(5, TimeUnit.SECONDS))
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val saved = executor.submit { writer.locked { writer.save(replacements) } }
            assertTrue(firstPublished.await(5, TimeUnit.SECONDS))
            val read = executor.submit<List<String>> {
                readerStarted.countDown()
                val reader = PiConfigPersistence(workspace)
                reader.locked { reader.files.map { it.readText() } }.also { readerFinished.countDown() }
            }
            assertTrue(readerStarted.await(5, TimeUnit.SECONDS))
            assertFalse(readerFinished.await(100, TimeUnit.MILLISECONDS))
            continuePublication.countDown()
            saved.get(5, TimeUnit.SECONDS)
            assertEquals(replacements.map { "$it\n" }, read.get(5, TimeUnit.SECONDS))
        } finally {
            continuePublication.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun eachEarlierFileEditChangesCacheStateEvenWhenMaximumTimestampIsUnchanged() = inWorkspace { workspace ->
        val persistence = initialized(workspace)
        persistence.files.forEachIndexed { index, file -> assertTrue(file.setLastModified((index + 1) * 1000L)) }
        persistence.files.forEach { file ->
            val before = persistence.fileStates()
            file.writeText(file.readText() + " edited")
            assertTrue(file.setLastModified(1500L))
            val after = persistence.fileStates()
            assertNotEquals(before, after)
            if (file != persistence.files.last()) {
                assertEquals(before.maxOf { it.modified }, after.maxOf { it.modified })
            }
        }
        val beforeRemoval = persistence.fileStates()
        assertTrue(persistence.files[0].delete())
        assertNotEquals(beforeRemoval, persistence.fileStates())
    }

    @Test fun sameSizeSameTimestampReplacementStillChangesCacheIdentity() = inWorkspace { workspace ->
        val persistence = initialized(workspace)
        val original = persistence.files[1]
        val timestamp = Files.getLastModifiedTime(original.toPath())
        val before = persistence.fileStates()
        val replacement = File(original.parentFile, "replacement")
        replacement.writeText("x".repeat(original.length().toInt()))
        Files.setLastModifiedTime(replacement.toPath(), timestamp)
        move(replacement, original)
        val after = persistence.fileStates()
        assertEquals(before[1].modified, after[1].modified)
        assertEquals(before[1].size, after[1].size)
        assertNotEquals(before, after)
    }


    private fun initialized(workspace: File): PiConfigPersistence = PiConfigPersistence(workspace).also { persistence ->
        persistence.files.forEachIndexed { index, file ->
            assertTrue(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            file.writeText(originals[index])
        }
    }

    private fun move(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun inWorkspace(block: (File) -> Unit) {
        val workspace = Files.createTempDirectory("pi-config-transaction").toFile()
        try { block(workspace) } finally { workspace.deleteRecursively() }
    }
}
