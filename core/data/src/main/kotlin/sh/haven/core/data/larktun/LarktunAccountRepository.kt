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
import sh.haven.core.security.CredentialEncryption
import javax.inject.Inject
import javax.inject.Singleton

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

    val session: Flow<LarktunSession?> = dataStore.data.map { prefs ->
        decodeSession(prefs)
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
}
