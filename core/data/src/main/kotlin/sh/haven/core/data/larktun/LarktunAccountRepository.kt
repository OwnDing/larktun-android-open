package sh.haven.core.data.larktun

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.security.CredentialEncryption
import javax.inject.Inject
import javax.inject.Singleton

data class LarktunSshCredential(
    val id: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val updatedAt: Long,
)

@Singleton
class LarktunAccountRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val authClient: LarktunAuthClient,
    @ApplicationContext private val context: Context,
) {
    private val authKeyKey = stringPreferencesKey("larktun_auth_key")
    private val serverUrlKey = stringPreferencesKey("larktun_server_url")
    private val accessTokenKey = stringPreferencesKey("larktun_access_token")
    private val accountJsonKey = stringPreferencesKey("larktun_account_json")
    private val entitlementJsonKey = stringPreferencesKey("larktun_entitlement_json")
    private val createdAtKey = longPreferencesKey("larktun_session_created_at")
    private val sshCredentialsJsonKey = stringPreferencesKey("larktun_ssh_credentials_json")

    val session: Flow<LarktunSession?> = dataStore.data.map { prefs ->
        decodeSession(prefs)
    }

    val sshCredentials: Flow<List<LarktunSshCredential>> = dataStore.data.map { prefs ->
        decodeSshCredentials(prefs[sshCredentialsJsonKey])
    }

    suspend fun fetchCaptcha(): LarktunCaptcha = authClient.fetchCaptcha()

    suspend fun login(
        username: String,
        password: String,
        captchaCode: String,
        captchaId: String,
    ): LarktunSession {
        val response = authClient.deviceLogin(
            LarktunDeviceLoginRequest(
                username = username,
                password = password,
                captchaCode = captchaCode,
                captchaId = captchaId,
            ),
        )
        val session = LarktunSession(
            authKey = response.authKey,
            serverUrl = response.serverUrl,
            accessToken = response.accessToken.takeIf { it.isNotBlank() },
            account = response.account ?: LarktunAccount(
                id = null,
                username = username,
                appleAccountToken = null,
            ),
            entitlement = response.entitlement ?: LarktunEntitlement.free(),
            createdAt = System.currentTimeMillis(),
        )
        saveSession(session)
        return session
    }

    suspend fun saveManualSession(authKey: String, serverUrl: String): LarktunSession {
        val session = LarktunSession(
            authKey = authKey,
            serverUrl = serverUrl,
            accessToken = null,
            account = null,
            entitlement = null,
            createdAt = System.currentTimeMillis(),
        )
        saveSession(session)
        return session
    }

    suspend fun signOut() {
        dataStore.edit { prefs ->
            prefs.remove(authKeyKey)
            prefs.remove(serverUrlKey)
            prefs.remove(accessTokenKey)
            prefs.remove(accountJsonKey)
            prefs.remove(entitlementJsonKey)
            prefs.remove(createdAtKey)
            prefs.remove(sshCredentialsJsonKey)
        }
    }

    suspend fun saveSshCredential(host: String, port: Int, username: String, password: String) {
        val cleanHost = host.trim()
        val cleanUsername = username.trim()
        if (cleanHost.isBlank() || cleanUsername.isBlank() || password.isBlank()) return

        dataStore.edit { prefs ->
            val existing = decodeSshCredentials(prefs[sshCredentialsJsonKey])
                .filterNot {
                    it.host.equals(cleanHost, ignoreCase = true) &&
                        it.port == port &&
                        it.username == cleanUsername
                }
            val next = existing + LarktunSshCredential(
                id = sshCredentialId(cleanHost, port, cleanUsername),
                host = cleanHost,
                port = port,
                username = cleanUsername,
                password = password,
                updatedAt = System.currentTimeMillis(),
            )
            prefs[sshCredentialsJsonKey] = encodeSshCredentials(next)
        }
    }

    suspend fun forgetSshCredential(host: String, port: Int, username: String) {
        val cleanHost = host.trim()
        val cleanUsername = username.trim()
        dataStore.edit { prefs ->
            val next = decodeSshCredentials(prefs[sshCredentialsJsonKey])
                .filterNot {
                    it.host.equals(cleanHost, ignoreCase = true) &&
                        it.port == port &&
                        it.username == cleanUsername
                }
            if (next.isEmpty()) {
                prefs.remove(sshCredentialsJsonKey)
            } else {
                prefs[sshCredentialsJsonKey] = encodeSshCredentials(next)
            }
        }
    }

    private suspend fun saveSession(session: LarktunSession) {
        dataStore.edit { prefs ->
            prefs[authKeyKey] = CredentialEncryption.encrypt(context, session.authKey)
            prefs[serverUrlKey] = session.serverUrl
            session.accessToken?.let {
                prefs[accessTokenKey] = CredentialEncryption.encrypt(context, it)
            } ?: prefs.remove(accessTokenKey)
            session.account?.let {
                prefs[accountJsonKey] = it.toJsonString()
            } ?: prefs.remove(accountJsonKey)
            session.entitlement?.let {
                prefs[entitlementJsonKey] = it.toJsonString()
            } ?: prefs.remove(entitlementJsonKey)
            prefs[createdAtKey] = session.createdAt
        }
    }

    private fun decodeSession(prefs: Preferences): LarktunSession? {
        val encryptedAuthKey = prefs[authKeyKey] ?: return null
        val serverUrl = prefs[serverUrlKey]?.takeIf { it.isNotBlank() } ?: return null
        val authKey = CredentialEncryption.decrypt(context, encryptedAuthKey)
        val accessToken = prefs[accessTokenKey]?.let {
            CredentialEncryption.decrypt(context, it)
        }?.takeIf { it.isNotBlank() }
        return LarktunSession(
            authKey = authKey,
            serverUrl = serverUrl,
            accessToken = accessToken,
            account = parseLarktunAccount(prefs[accountJsonKey]),
            entitlement = parseLarktunEntitlement(prefs[entitlementJsonKey]),
            createdAt = prefs[createdAtKey] ?: 0L,
        )
    }

    private fun encodeSshCredentials(credentials: List<LarktunSshCredential>): String {
        val array = JSONArray()
        credentials
            .sortedByDescending { it.updatedAt }
            .take(20)
            .forEach { credential ->
                array.put(
                    JSONObject()
                        .put("id", credential.id)
                        .put("host", credential.host)
                        .put("port", credential.port)
                        .put("username", credential.username)
                        .put("password", CredentialEncryption.encrypt(context, credential.password))
                        .put("updatedAt", credential.updatedAt),
                )
            }
        return array.toString()
    }

    private fun decodeSshCredentials(json: String?): List<LarktunSshCredential> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val host = obj.optString("host").trim()
                    val username = obj.optString("username").trim()
                    val encryptedPassword = obj.optString("password").takeIf { it.isNotBlank() }
                    if (host.isBlank() || username.isBlank() || encryptedPassword == null) continue
                    val port = obj.optInt("port", 22)
                    add(
                        LarktunSshCredential(
                            id = obj.optString("id")
                                .takeIf { it.isNotBlank() }
                                ?: sshCredentialId(host, port, username),
                            host = host,
                            port = port,
                            username = username,
                            password = CredentialEncryption.decrypt(context, encryptedPassword),
                            updatedAt = obj.optLong("updatedAt", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun sshCredentialId(host: String, port: Int, username: String): String =
        listOf(host.lowercase(), port.toString(), username).joinToString("|")
}
