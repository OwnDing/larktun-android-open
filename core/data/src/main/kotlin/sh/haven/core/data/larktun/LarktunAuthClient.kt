package sh.haven.core.data.larktun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LarktunAuthClient @Inject constructor() {
    suspend fun fetchCaptcha(): LarktunCaptcha = withContext(Dispatchers.IO) {
        val data = send(path = "/api/auth/captcha", method = "GET", body = null)
        LarktunCaptcha(
            captchaId = data.optStringOrNull("captchaId")
                ?: throw LarktunApiException("Captcha response is missing captchaId"),
            imageBase64 = data.optString("imageBase64", ""),
            expiresIn = data.optLongOrNull("expiresIn") ?: 0L,
        )
    }

    suspend fun deviceLogin(request: LarktunDeviceLoginRequest): LarktunDeviceLoginResponse =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("username", request.username)
                put("password", request.password)
                put("captchaCode", request.captchaCode)
                put("captchaId", request.captchaId)
            }.toString().toByteArray(Charsets.UTF_8)

            val data = send(path = "/api/auth/device-login", method = "POST", body = body)
            LarktunDeviceLoginResponse(
                authKey = data.optStringOrNull("authKey")
                    ?: throw LarktunApiException("Login response is missing authKey"),
                serverUrl = data.optStringOrNull("serverUrl")
                    ?: throw LarktunApiException("Login response is missing serverUrl"),
                accessToken = data.optStringOrNull("accessToken").orEmpty(),
                account = parseLarktunAccount(data.optJSONObject("account")),
                entitlement = parseLarktunEntitlement(data.optJSONObject("entitlement")),
            )
        }

    private fun send(path: String, method: String, body: ByteArray?): JSONObject {
        val endpoint = URL(LarktunConfig.AUTH_BASE_URL.trimEnd('/') + path)
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { it.write(body) }
            }

            val responseCode = connection.responseCode
            val responseBytes = (if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.use { it.readBytes() } ?: ByteArray(0)

            val root = JSONObject(String(responseBytes, Charsets.UTF_8))
            val success = root.optBoolean("success", false)
            val code = root.optInt("code", -1)
            val message = root.optString("message", "")
            val data = root.optJSONObject("data")
            if (!success || code != 0 || data == null) {
                throw LarktunApiException(message.ifBlank { "Larktun API request failed" }, code)
            }
            return data
        } finally {
            connection.disconnect()
        }
    }
}
