package sh.haven.core.tunnel

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sh.haven.core.data.larktun.LarktunConfig
import sh.haven.core.data.larktun.LarktunSession

@Singleton
class LarktunTailnetManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val SHARED_TUNNEL_CONFIG_ID = "__larktun_account_tailnet__"
        private const val TAG = "LarktunTailnetManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _status = MutableStateFlow(LarktunTailnetStatus.idle())
    val status: StateFlow<LarktunTailnetStatus> = _status.asStateFlow()

    private var currentSessionKey: String? = null
    private var tunnel: TailscaleTunnel? = null
    private var pollingJob: Job? = null
    private var webProxy: LarktunHttpProxy? = null

    suspend fun applySession(session: LarktunSession?) {
        if (session == null) {
            stop()
        } else {
            start(session)
        }
    }

    suspend fun refresh() {
        refreshStatus()
    }

    suspend fun getRunningTunnel(): Tunnel? = mutex.withLock { tunnel }

    suspend fun ping(address: String, timeoutMs: Int = 3_000): LarktunPingResult {
        val snapshot = mutex.withLock { tunnel }
            ?: throw IllegalStateException("Larktun network is not running")
        return withContext(Dispatchers.IO) {
            LarktunPingResult.fromJson(snapshot.pingJson(address, timeoutMs))
        }
    }

    suspend fun startWebProxy(host: String, port: Int = 80): LarktunWebProxySession {
        val snapshot = mutex.withLock { tunnel }
            ?: throw IllegalStateException("Larktun network is not running")
        val proxy = LarktunHttpProxy(
            tunnel = snapshot,
            scope = scope,
        )
        val proxyUrl = withContext(Dispatchers.IO) {
            proxy.start()
        }
        mutex.withLock {
            webProxy?.close()
            webProxy = proxy
        }
        scope.launch {
            delay(10 * 60 * 1_000L)
            mutex.withLock {
                if (webProxy === proxy) {
                    webProxy?.close()
                    webProxy = null
                }
            }
        }
        return LarktunWebProxySession(
            proxyUrl = proxyUrl,
            initialUrl = initialWebUrl(host = host, port = port),
        )
    }

    private fun initialWebUrl(host: String, port: Int): String {
        val urlHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        val portPart = if (port == 80) "" else ":$port"
        return "http://$urlHost$portPart/"
    }

    private suspend fun start(session: LarktunSession) = mutex.withLock {
        val nextSessionKey = session.sessionKey()
        if (currentSessionKey == nextSessionKey && tunnel != null) {
            tunnel?.let { _status.value = readStatus(it) }
            return@withLock
        }

        stopLocked()
        _status.value = LarktunTailnetStatus.starting()

        try {
            val stateDir = File(context.filesDir, "larktun-tailnet-$nextSessionKey").also {
                it.mkdirs()
            }
            tunnel = withContext(Dispatchers.IO) {
                TailscaleTunnel(
                    authKey = session.authKey,
                    stateDir = stateDir,
                    hostname = buildHostname(),
                    controlURL = session.serverUrl,
                    defaultRouteInterface = AndroidDefaultRouteInterface.current(context),
                )
            }
            currentSessionKey = nextSessionKey
            tunnel?.let { _status.value = readStatus(it) }
            pollingJob = scope.launch {
                while (isActive) {
                    delay(5_000)
                    refreshStatus()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start Larktun network", t)
            stopLocked()
            _status.value = LarktunTailnetStatus.error(
                t.message ?: "Failed to start Larktun network",
            )
        }
    }

    private suspend fun stop() = mutex.withLock {
        stopLocked()
        _status.value = LarktunTailnetStatus.idle()
    }

    private fun stopLocked() {
        pollingJob?.cancel()
        pollingJob = null
        webProxy?.close()
        webProxy = null
        try {
            tunnel?.close()
        } catch (_: Throwable) {
            // Best-effort teardown.
        }
        tunnel = null
        currentSessionKey = null
    }

    private suspend fun refreshStatus() {
        val snapshot = mutex.withLock { tunnel }
        if (snapshot == null) {
            _status.value = LarktunTailnetStatus.idle()
            return
        }

        _status.value = readStatus(snapshot)
    }

    private suspend fun readStatus(snapshot: TailscaleTunnel): LarktunTailnetStatus =
        withContext(Dispatchers.IO) {
            runCatching {
                LarktunTailnetStatus.fromJson(snapshot.statusJson())
            }.getOrElse { error ->
                LarktunTailnetStatus.error(error.message ?: "Failed to read Larktun devices")
            }
        }

    private fun buildHostname(): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty().takeLast(6)
        return sanitizeHostname(
            listOfNotNull(
                LarktunConfig.ANDROID_HOSTNAME_PREFIX,
                Build.MODEL,
                androidId.takeIf { it.isNotBlank() },
            ).joinToString("-"),
        )
    }

    private fun LarktunSession.sessionKey(): String {
        val stableIdentity = listOfNotNull(
            serverUrl,
            account?.id,
            account?.username,
            accessToken,
            authKey.take(16),
        ).joinToString("|")
        return sha256(stableIdentity).take(16)
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sanitizeHostname(value: String): String {
        val cleaned = value.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
            .take(63)
            .trim('-')
        return cleaned.ifBlank { LarktunConfig.ANDROID_HOSTNAME_PREFIX }
    }
}
