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
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HTTP_HEADER_LIMIT_BYTES = 64 * 1024
private const val HTTP_PROXY_TIMEOUT_MS = 30_000
private const val HTTP_PROXY_IDLE_TIMEOUT_MS = 90_000

data class LarktunWebProxySession(
    val proxyUrl: String,
    val initialUrl: String,
)

/**
 * Loopback-only HTTP forward proxy for Android WebView.
 *
 * WebView is configured to use this proxy, then requests normal target URLs
 * such as `http://nas.tailnet.ts.net/`. The proxy dials those hosts through
 * the app-only tsnet [Tunnel], including HTTPS via HTTP CONNECT.
 */
internal class LarktunHttpProxy(
    private val tunnel: Tunnel,
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
        return "http://127.0.0.1:${server.localPort}"
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
        client.soTimeout = HTTP_PROXY_IDLE_TIMEOUT_MS
        val clientInput = client.getInputStream()
        val clientOutput = client.getOutputStream()
        val headerBytes = readHeaders(clientInput)
        val headers = String(headerBytes, StandardCharsets.ISO_8859_1)
        val request = parseRequest(headers)
        if (request.method.equals("CONNECT", ignoreCase = true)) {
            handleConnect(request, clientInput, clientOutput)
        } else {
            handleHttp(request, headers, clientInput, clientOutput)
        }
    }

    private fun handleHttp(
        request: ProxyRequest,
        rawHeaders: String,
        clientInput: InputStream,
        clientOutput: OutputStream,
    ) {
        val target = httpTarget(request, rawHeaders)
        val remote = tunnel.dial(target.host, target.port, HTTP_PROXY_TIMEOUT_MS)
        try {
            val rewrittenHeaders = rewriteHttpHeaders(rawHeaders, request, target)
            remote.outputStream.write(rewrittenHeaders.toByteArray(StandardCharsets.ISO_8859_1))
            copyFixedRequestBody(rawHeaders, clientInput, remote.outputStream)
            remote.outputStream.flush()
            remote.inputStream.copyTo(clientOutput)
            clientOutput.flush()
        } finally {
            runCatching { remote.close() }
        }
    }

    private suspend fun handleConnect(
        request: ProxyRequest,
        clientInput: InputStream,
        clientOutput: OutputStream,
    ) {
        val target = parseAuthority(request.target, defaultPort = 443)
        val remote = tunnel.dial(target.host, target.port, HTTP_PROXY_TIMEOUT_MS)
        try {
            clientOutput.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            clientOutput.flush()
            pumpBidirectional(
                leftInput = clientInput,
                leftOutput = clientOutput,
                rightInput = remote.inputStream,
                rightOutput = remote.outputStream,
            )
        } finally {
            runCatching { remote.close() }
        }
    }

    private suspend fun pumpBidirectional(
        leftInput: InputStream,
        leftOutput: OutputStream,
        rightInput: InputStream,
        rightOutput: OutputStream,
    ) {
        val upstream = scope.launch(Dispatchers.IO) {
            runCatching {
                leftInput.copyTo(rightOutput)
                rightOutput.flush()
            }
        }
        val downstream = scope.launch(Dispatchers.IO) {
            runCatching {
                rightInput.copyTo(leftOutput)
                leftOutput.flush()
            }
        }
        upstream.join()
        downstream.cancel()
    }

    private fun rewriteHttpHeaders(
        rawHeaders: String,
        request: ProxyRequest,
        target: ProxyTarget,
    ): String {
        val path = target.pathAndQuery.ifBlank { "/" }
        return buildString {
            append(request.method).append(' ')
                .append(path)
                .append(' ')
                .append(request.version)
                .append("\r\n")
            rawHeaders.split("\r\n").drop(1).forEach { line ->
                if (line.isBlank()) return@forEach
                val name = line.substringBefore(':', missingDelimiterValue = "")
                if (name.equals("Connection", ignoreCase = true)) return@forEach
                if (name.equals("Proxy-Connection", ignoreCase = true)) return@forEach
                if (name.equals("Proxy-Authorization", ignoreCase = true)) return@forEach
                if (name.equals("Host", ignoreCase = true)) return@forEach
                append(line).append("\r\n")
            }
            append("Host: ").append(target.hostHeader).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
    }

    private fun httpTarget(request: ProxyRequest, rawHeaders: String): ProxyTarget {
        if (request.target.startsWith("http://", ignoreCase = true) ||
            request.target.startsWith("https://", ignoreCase = true)
        ) {
            val uri = URI(request.target)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http") {
                throw IOException("HTTPS requests must use CONNECT")
            }
            val host = uri.host ?: throw IOException("missing request host")
            val port = if (uri.port > 0) uri.port else 80
            val rawPath = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
            val pathAndQuery = uri.rawQuery?.let { "$rawPath?$it" } ?: rawPath
            return ProxyTarget(
                host = host,
                port = port,
                hostHeader = authorityForHeader(host, port, defaultPort = 80),
                pathAndQuery = pathAndQuery,
            )
        }

        val hostHeader = rawHeaders.lineSequence()
            .firstOrNull { it.startsWith("Host:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?: throw IOException("missing Host header")
        val target = parseAuthority(hostHeader, defaultPort = 80)
        return target.copy(pathAndQuery = request.target.ifBlank { "/" })
    }

    private fun parseRequest(rawHeaders: String): ProxyRequest {
        val requestLine = rawHeaders.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
            ?: throw IOException("empty HTTP request")
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size != 3) throw IOException("invalid HTTP request")
        return ProxyRequest(
            method = parts[0],
            target = parts[1],
            version = parts[2],
        )
    }

    private fun parseAuthority(rawAuthority: String, defaultPort: Int): ProxyTarget {
        val authority = rawAuthority.trim()
        if (authority.isBlank()) throw IOException("empty proxy target")
        val uriAuthority = if (authority.contains("://")) {
            URI(authority).rawAuthority ?: throw IOException("invalid proxy target")
        } else {
            authority
        }

        if (uriAuthority.startsWith("[")) {
            val close = uriAuthority.indexOf(']')
            if (close <= 1) throw IOException("invalid IPv6 proxy target")
            val host = uriAuthority.substring(1, close)
            val port = uriAuthority.substring(close + 1)
                .removePrefix(":")
                .takeIf { it.isNotBlank() }
                ?.toIntOrNull()
                ?: defaultPort
            return ProxyTarget(host, port, authorityForHeader(host, port, defaultPort), "/")
        }

        val lastColon = uriAuthority.lastIndexOf(':')
        val hasSinglePortSeparator = lastColon > 0 && uriAuthority.indexOf(':') == lastColon
        val host = if (hasSinglePortSeparator) uriAuthority.substring(0, lastColon) else uriAuthority
        val port = if (hasSinglePortSeparator) {
            uriAuthority.substring(lastColon + 1).toIntOrNull() ?: defaultPort
        } else {
            defaultPort
        }
        return ProxyTarget(host, port, authorityForHeader(host, port, defaultPort), "/")
    }

    private fun authorityForHeader(host: String, port: Int, defaultPort: Int): String {
        val normalizedHost = if (host.contains(':') && !host.startsWith("[")) {
            "[$host]"
        } else {
            host
        }
        return if (port == defaultPort) normalizedHost else "$normalizedHost:$port"
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

    private data class ProxyRequest(
        val method: String,
        val target: String,
        val version: String,
    )

    private data class ProxyTarget(
        val host: String,
        val port: Int,
        val hostHeader: String,
        val pathAndQuery: String,
    )
}
