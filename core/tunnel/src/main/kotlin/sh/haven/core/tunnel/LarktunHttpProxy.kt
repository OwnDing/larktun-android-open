package sh.haven.core.tunnel

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HTTP_HEADER_LIMIT_BYTES = 64 * 1024
private const val HTTP_PROXY_TIMEOUT_MS = 30_000

internal class LarktunHttpProxy(
    private val tunnel: Tunnel,
    private val remoteHost: String,
    private val remotePort: Int,
    private val scope: CoroutineScope,
) : Closeable {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    fun start(): String {
        check(serverSocket == null) { "proxy already started" }
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(
                InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"),
                    0,
                ),
            )
        }
        serverSocket = server
        acceptJob = scope.launch {
            acceptLoop(server)
        }
        return "http://127.0.0.1:${server.localPort}/"
    }

    override fun close() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            scope.launch {
                runCatching { handleClient(client) }
                runCatching { client.close() }
            }
        }
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        client.soTimeout = HTTP_PROXY_TIMEOUT_MS
        var remote: TunneledConnection? = null
        try {
            remote = tunnel.dial(remoteHost, remotePort, HTTP_PROXY_TIMEOUT_MS)
            val clientInput = client.getInputStream()
            val clientOutput = client.getOutputStream()
            val remoteInput = remote.inputStream
            val remoteOutput = remote.outputStream
            val headerBytes = readHeaders(clientInput)
            val headers = String(headerBytes, StandardCharsets.ISO_8859_1)
            val rewrittenHeaders = rewriteHeaders(headers)
            remoteOutput.write(rewrittenHeaders.toByteArray(StandardCharsets.ISO_8859_1))
            copyFixedRequestBody(headers, clientInput, remoteOutput)
            remoteOutput.flush()
            remoteInput.copyTo(clientOutput)
            clientOutput.flush()
        } finally {
            runCatching { remote?.close() }
            runCatching { client.close() }
        }
    }

    private fun rewriteHeaders(rawHeaders: String): String {
        val lines = rawHeaders.split("\r\n")
        val requestLine = lines.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: throw IOException("empty HTTP request")
        return buildString {
            append(requestLine).append("\r\n")
            lines.drop(1).forEach { line ->
                if (line.isBlank()) return@forEach
                val name = line.substringBefore(':', missingDelimiterValue = "")
                if (name.equals("Host", ignoreCase = true)) return@forEach
                if (name.equals("Connection", ignoreCase = true)) return@forEach
                if (name.equals("Proxy-Connection", ignoreCase = true)) return@forEach
                append(line).append("\r\n")
            }
            append("Host: ").append(hostHeader()).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
    }

    private fun hostHeader(): String {
        val host = if (remoteHost.contains(':') && !remoteHost.startsWith("[")) {
            "[$remoteHost]"
        } else {
            remoteHost
        }
        return if (remotePort == 80) host else "$host:$remotePort"
    }

    private fun copyFixedRequestBody(
        rawHeaders: String,
        input: InputStream,
        output: OutputStream,
    ) {
        val contentLength = rawHeaders
            .lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toLongOrNull()
            ?: return
        var remaining = contentLength
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) throw IOException("unexpected EOF while reading HTTP body")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun readHeaders(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        while (buffer.size() < HTTP_HEADER_LIMIT_BYTES) {
            val next = input.read()
            if (next == -1) throw IOException("HTTP request ended before headers completed")
            buffer.write(next)
            val bytes = buffer.toByteArray()
            if (bytes.endsWithHeaderTerminator()) {
                return bytes
            }
        }
        throw IOException("HTTP request headers exceed $HTTP_HEADER_LIMIT_BYTES bytes")
    }

    private fun ByteArray.endsWithHeaderTerminator(): Boolean =
        size >= 4 &&
            this[size - 4] == '\r'.code.toByte() &&
            this[size - 3] == '\n'.code.toByte() &&
            this[size - 2] == '\r'.code.toByte() &&
            this[size - 1] == '\n'.code.toByte()
}
