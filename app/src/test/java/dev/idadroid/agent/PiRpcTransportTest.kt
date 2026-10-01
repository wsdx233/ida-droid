package dev.idadroid.agent

import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PiRpcTransportTest {
    @Test fun correlatesOutOfOrderResponsesWithoutTreatingPromptAckAsCompletion() = runBlocking {
        val process = PipeProcess()
        val events = mutableListOf<String>()
        val transport = PiRpcTransport(process, { synchronized(events) { events += it.getValue("type").jsonPrimitive.content } }, {})
        try {
            val prompt = async { transport.request("prompt", timeoutMs = 2_000) }
            val stats = async { transport.request("get_session_stats", timeoutMs = 2_000) }
            val requests = withContext(Dispatchers.IO) { listOf(process.readCommand(), process.readCommand()) }.associateBy { it.getValue("type").jsonPrimitive.content }
            process.respond(requests.getValue("get_session_stats"), buildJsonObject { put("cost", 7) })
            process.emit("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"a\u2028b\u2029c"}}""")
            process.respond(requests.getValue("prompt"), buildJsonObject { put("disposition", "started") })
            assertEquals(7, stats.await().getValue("cost").jsonPrimitive.int)
            assertEquals("started", prompt.await().getValue("disposition").jsonPrimitive.content)
            assertEquals(listOf("message_update"), synchronized(events) { events.toList() })
            assertTrue(process.isAlive)
        } finally { transport.close() }
    }

    @Test fun malformedOutputFailsEveryOutstandingRequestAndKillsProcess() = runBlocking {
        val process = PipeProcess()
        val failure = CompletableDeferred<Throwable>()
        val transport = PiRpcTransport(process, {}, { failure.complete(it) })
        try {
            supervisorScope {
                val first = async { runCatching { transport.request("get_state", timeoutMs = 2_000) } }
                val second = async { runCatching { transport.request("get_messages", timeoutMs = 2_000) } }
                withContext(Dispatchers.IO) { process.readCommand(); process.readCommand() }
                process.emit("not json")
                withTimeout(2_000) { failure.await() }
                assertTrue(first.await().exceptionOrNull() is IOException)
                assertTrue(second.await().exceptionOrNull() is IOException)
                assertFalse(process.isAlive)
            }
        } finally { transport.close() }
    }

    @Test fun missingResponseHasDeadlineAndCannotLeaveLiveProcess() = runBlocking {
        val process = PipeProcess()
        val transport = PiRpcTransport(process, {}, {})
        try {
            val result = runCatching { transport.request("get_state", timeoutMs = 100) }
            assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
            assertFalse(process.isAlive)
        } finally { transport.close() }
    }

    @Test fun eofFailsPendingRequestInsteadOfWaitingForItsDeadline() = runBlocking {
        val process = PipeProcess()
        val transport = PiRpcTransport(process, {}, {})
        try {
            val result = async { runCatching { transport.request("get_state", timeoutMs = 10_000) } }
            withContext(Dispatchers.IO) { process.readCommand() }
            process.stdout.close()
            assertTrue(withTimeout(2_000) { result.await() }.exceptionOrNull() is IOException)
        } finally { transport.close() }
    }

    private class PipeProcess : Process() {
        private val input = PipedInputStream(64 * 1024)
        val stdout = PipedOutputStream(input)
        private val errors = PipedInputStream(64 * 1024)
        private val stderr = PipedOutputStream(errors)
        private val commands = PipedInputStream(64 * 1024)
        private val stdin = PipedOutputStream(commands)
        private val reader = commands.bufferedReader()
        private val stopped = CountDownLatch(1)
        fun readCommand() = Json.parseToJsonElement(reader.readLine()).jsonObject
        fun emit(record: String) { stdout.write((record + "\n").toByteArray()); stdout.flush() }
        fun respond(command: JsonObject, data: JsonObject) = emit(buildJsonObject {
            put("type", "response"); put("id", command.getValue("id")); put("command", command.getValue("type"))
            put("success", true); put("data", data)
        }.toString())
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = errors
        override fun waitFor(): Int { stopped.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = stopped.await(timeout, unit)
        override fun exitValue(): Int { if (isAlive) throw IllegalThreadStateException(); return 0 }
        override fun isAlive(): Boolean = stopped.count > 0
        override fun destroy() {
            stopped.countDown()
            runCatching { stdout.close() }; runCatching { stderr.close() }; runCatching { stdin.close() }
        }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
}
