package dev.idadroid.mcp

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A minimal HTTP server that exposes [FileTransferManager] to the agent running
 * inside the proot container. Because the container shares the host's network
 * namespace, the agent can reach this server via `curl http://127.0.0.1:<port>/...`.
 *
 * Endpoints:
 *  - `GET  /api/transfers`          → JSON manifest of all transfer entries
 *  - `GET  /api/transfers?name=foo` → single entry matching `foo` (or 404)
 *  - `POST /api/transfer?path=<host_path>` → copy a host file into the container
 *  - `POST /api/transfer-and-open?name=foo` → search host + transfer into container
 *  - `POST /api/open-in-ida?name=foo` → one-shot: search host → transfer → call
 *    ida-mcp `open_file` so IDA Pro opens the file immediately. External tools
 *    (desktop IDEs, scripts, share-sheets) can use this to push a file into IDA
 *    without manually running `mcpc call open_file` inside the container.
 *  - `DELETE /api/transfers?id=<id>` → remove a transfer entry and its file
 *  - `GET  /health`                 → `{"status":"ok"}`
 *
 * @param idaMcpEndpointProvider returns the current ida-mcp HTTP endpoint
 *  (e.g. `http://127.0.0.1:8765`) when the ida-mcp server is running, or null
 *  when it is stopped. The `/api/open-in-ida` endpoint uses this to invoke the
 *  `open_file` tool; when null, it returns 503 so the caller knows to start
 *  IDA MCP first.
 */
class FileTransferHttpServer(
    private val manager: FileTransferManager,
    private val port: Int = DEFAULT_PORT,
    private val idaMcpEndpointProvider: () -> String? = { null }
) {
    @Volatile private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var acceptJob: Job? = null

    val isRunning: Boolean get() = running
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    fun start() {
        if (running) return
        // Recreate the scope if it was previously cancelled by stop().
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        val socket = ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        running = true
        acceptJob = scope.launch {
            Log.i(TAG, "Listening on 127.0.0.1:${socket.localPort}")
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "accept() failed", e)
                    break
                }
                launch { handleClient(client) }
            }
        }
    }

    fun stop() {
        running = false
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        // Cancel the scope but DO NOT make it un-restartable — start() recreates
        // the scope if needed so the server can be started again after stop().
        scope.cancel()
    }

    private suspend fun handleClient(client: java.net.Socket) {
        client.use { socket ->
            socket.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val output = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respondText(output, 400, "Bad Request")
                return
            }
            val method = parts[0].uppercase()
            val rawPath = parts[1]

            // Read headers
            val headers = mutableMapOf<String, String>()
            var headerCount = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                headerCount++
                if (headerCount > MAX_HEADERS) {
                    respondJson(output, 431, "{\"error\":\"too many headers (max $MAX_HEADERS)\"}")
                    return
                }
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }

            // Read body if present (read raw bytes from the underlying stream)
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                if (contentLength > MAX_BODY_BYTES) {
                    respondJson(output, 413, "{\"error\":\"request body too large (max ${MAX_BODY_BYTES} bytes)\"}")
                    return
                }
                val rawInput = socket.getInputStream()
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = rawInput.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8)
            } else ""

            val pathOnly = rawPath.substringBefore('?')
            val queryStr = rawPath.substringAfter('?', "")
            val query = parseQuery(queryStr)

            try {
                route(method, pathOnly, query, body, output)
            } catch (e: Exception) {
                Log.e(TAG, "route error: $method $pathOnly", e)
                respondJson(output, 500, "{\"error\":\"${escapeJson(e.message ?: "Internal error")}\"}")
            }
        }
    }

    private suspend fun route(
        method: String,
        path: String,
        query: Map<String, String>,
        body: String,
        output: OutputStream
    ) {
        when {
            path == "/health" && method == "GET" ->
                respondJson(output, 200, "{\"status\":\"ok\",\"port\":$port}")

            path == "/api/transfers" && method == "GET" -> {
                val name = query["name"]
                if (name != null) {
                    val entry = manager.findTransfer(name)
                    if (entry != null) {
                        respondJson(output, 200, entryJson(entry))
                    } else {
                        respondJson(output, 404, "{\"error\":\"no transfer matching '" + escapeJson(name) + "'\"}")
                    }
                } else {
                    // 全量列表不暴露 hostPath：宿主绝对路径在容器内不可访问，会误导 AI。
                    // 只返回容器内可用的 prootPath 等字段（与单条 entryJson 一致）。
                    val entries = manager.listTransfers().joinToString(",") { entryJson(it) }
                    respondJson(output, 200, "{\"entries\":[$entries]}")
                }
            }

            // 最近上传的文件（默认5分钟内），供 agent 检查是否有可推荐的上传文件
            path == "/api/transfers/latest" && method == "GET" -> {
                val latest = manager.latestTransfer()
                if (latest != null) {
                    respondJson(output, 200, entryJson(latest))
                } else {
                    respondJson(output, 404, "{\"error\":\"no transfers\"}")
                }
            }

            path == "/api/transfers/recent" && method == "GET" -> {
                val withinMs = query["within_ms"]?.toLongOrNull() ?: 300_000
                val recent = manager.recentTransfers(withinMs)
                val entriesJson = recent.joinToString(",") { entryJson(it) }
                respondJson(output, 200, "{\"transfers\":[$entriesJson]}")
            }

            path == "/api/transfer" && method == "POST" -> {
                val hostPath = query["path"]
                if (hostPath.isNullOrBlank()) {
                    respondJson(output, 400, "{\"error\":\"missing 'path' query parameter\"}")
                    return
                }
                val entry = manager.transferHostPath(hostPath)
                respondJson(output, 200, entryJson(entry))
            }

            // 按文件名搜索主机并自动传输进容器——给 idadroid-file open 用
            path == "/api/transfer-and-open" && method == "POST" -> {
                val name = query["name"]
                if (name.isNullOrBlank()) {
                    respondJson(output, 400, "{\"error\":\"missing 'name' query parameter\"}")
                    return
                }
                val entry = manager.findAndTransferByName(name)
                if (entry != null) {
                    respondJson(output, 200, entryJson(entry))
                } else {
                    respondJson(output, 404, "{\"error\":\"file not found on host: " + escapeJson(name) + "\"}")
                }
            }

            // 一站式：搜索主机 → 传输进容器 → 调用 ida-mcp open_file 让 IDA 打开
            // 外部工具（桌面 IDE / 脚本 / 分享面板）只需一个 HTTP 请求即可把文件推给 IDA。
            path == "/api/open-in-ida" && method == "POST" -> {
                val name = query["name"]
                val hostPath = query["path"]
                if (name.isNullOrBlank() && hostPath.isNullOrBlank()) {
                    respondJson(output, 400, "{\"error\":\"missing 'name' or 'path' query parameter\"}")
                    return
                }
                val endpoint = idaMcpEndpointProvider()
                if (endpoint.isNullOrBlank()) {
                    respondJson(output, 503, "{\"error\":\"IDA MCP server is not running; start it first\"}")
                    return
                }
                // 1. 获取容器内路径：优先用已传输的，否则搜索+传输
                val transferResult = runCatching {
                    when {
                        !hostPath.isNullOrBlank() -> manager.transferHostPath(hostPath)
                        else -> {
                            val n = name ?: error("文件名为空")
                            manager.findAndTransferByName(n)
                        }
                    }
                }
                // runCatching 会吞 CancellationException，重新抛出以保留取消信号
                val transferCause = transferResult.exceptionOrNull()
                if (transferCause is kotlinx.coroutines.CancellationException) throw transferCause
                val entry = transferResult.getOrNull()
                if (entry == null) {
                    respondJson(output, 404, "{\"error\":\"file not found or transfer failed: ${escapeJson(name ?: hostPath ?: "")}\"}")
                    return
                }
                // 2. 调用 ida-mcp 的 open_file 工具
                val client = IdaMcpClient(endpoint)
                val toolResult = runCatching { client.openFile(entry.prootPath) }
                // runCatching 会吞 CancellationException，重新抛出以保留取消信号
                val cause = toolResult.exceptionOrNull()
                if (cause is kotlinx.coroutines.CancellationException) throw cause
                toolResult.onSuccess { resultText ->
                    respondJson(output, 200, openInIdaResultJson(entry, true, resultText))
                }.onFailure { err ->
                    respondJson(output, 200, openInIdaResultJson(entry, false, err.message ?: "open_file failed"))
                }
            }

            path == "/api/transfers" && method == "DELETE" -> {
                val id = query["id"]
                if (id.isNullOrBlank()) {
                    respondJson(output, 400, "{\"error\":\"missing 'id' query parameter\"}")
                    return
                }
                val ok = manager.removeTransfer(id)
                respondJson(output, if (ok) 200 else 404, "{\"removed\":$ok}")
            }

            else -> respondJson(output, 404, "{\"error\":\"not found: " + escapeJson(method) + " " + escapeJson(path) + "\"}")
        }
    }

    private fun entryJson(entry: FileTransferManager.TransferEntry): String {
        return """{"id":"${escapeJson(entry.id)}","name":"${escapeJson(entry.name)}",""" +
            """"prootPath":"${escapeJson(entry.prootPath)}","size":${entry.size},""" +
            """"mimeType":"${escapeJson(entry.mimeType)}","transferredAt":${entry.transferredAt}}"""
    }

    private fun openInIdaResultJson(
        entry: FileTransferManager.TransferEntry,
        opened: Boolean,
        message: String
    ): String {
        return """{"opened":$opened,"transfer":${entryJson(entry)},"message":"${escapeJson(message)}"}"""
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) pair to ""
            else URLDecoder.decode(pair.substring(0, eq), "UTF-8") to
                URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
        }.toMap()
    }

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun respondText(output: OutputStream, code: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code ${statusText(code)}\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun respondJson(output: OutputStream, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code ${statusText(code)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    companion object {
        private const val TAG = "FileTransferHttpServer"
        const val DEFAULT_PORT = 8766
        /** Maximum accepted HTTP request body size (10 MB). Prevents OOM from malicious Content-Length. */
        private const val MAX_BODY_BYTES = 10 * 1024 * 1024
        /** Maximum number of HTTP request headers. Prevents OOM from malicious clients sending endless headers. */
        private const val MAX_HEADERS = 100
    }
}
