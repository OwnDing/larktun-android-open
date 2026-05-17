package sh.haven.core.data.larktun

import org.json.JSONObject

object LarktunConfig {
    const val AUTH_BASE_URL = "https://larktun.com"
    const val DEFAULT_CONTROL_URL = "https://hs.larktun.com"
    const val ANDROID_HOSTNAME_PREFIX = "larktun-android"
}

data class LarktunCaptcha(
    val captchaId: String,
    val imageBase64: String,
    val expiresIn: Long,
)

data class LarktunDeviceLoginRequest(
    val username: String,
    val password: String,
    val captchaCode: String,
    val captchaId: String,
)

data class LarktunDeviceLoginResponse(
    val authKey: String,
    val serverUrl: String,
    val accessToken: String,
    val account: LarktunAccount?,
    val entitlement: LarktunEntitlement?,
)

data class LarktunAccount(
    val id: String?,
    val username: String?,
    val appleAccountToken: String?,
)

data class LarktunEntitlement(
    val source: String?,
    val planCode: String?,
    val status: String?,
    val deviceLimit: Int?,
    val deviceCount: Int?,
    val dedicatedDerperEnabled: Boolean?,
    val expiresAt: String?,
) {
    companion object {
        fun free(deviceCount: Int? = null): LarktunEntitlement = LarktunEntitlement(
            source = "free",
            planCode = "free",
            status = "active",
            deviceLimit = 5,
            deviceCount = deviceCount,
            dedicatedDerperEnabled = false,
            expiresAt = null,
        )
    }
}

data class LarktunSession(
    val authKey: String,
    val serverUrl: String,
    val accessToken: String?,
    val account: LarktunAccount?,
    val entitlement: LarktunEntitlement?,
    val createdAt: Long,
) {
    val accountName: String?
        get() = account?.username
}

class LarktunApiException(
    message: String,
    val code: Int? = null,
) : Exception(message)

internal fun LarktunAccount.toJsonString(): String = JSONObject().apply {
    putNullable("id", id)
    putNullable("username", username)
    putNullable("appleAccountToken", appleAccountToken)
}.toString()

internal fun LarktunEntitlement.toJsonString(): String = JSONObject().apply {
    putNullable("source", source)
    putNullable("planCode", planCode)
    putNullable("status", status)
    putNullable("deviceLimit", deviceLimit)
    putNullable("deviceCount", deviceCount)
    putNullable("dedicatedDerperEnabled", dedicatedDerperEnabled)
    putNullable("expiresAt", expiresAt)
}.toString()

internal fun parseLarktunAccount(json: String?): LarktunAccount? {
    if (json.isNullOrBlank()) return null
    val obj = JSONObject(json)
    return parseLarktunAccount(obj)
}

internal fun parseLarktunAccount(obj: JSONObject?): LarktunAccount? {
    if (obj == null) return null
    return LarktunAccount(
        id = obj.optStringOrNull("id"),
        username = obj.optStringOrNull("username"),
        appleAccountToken = obj.optStringOrNull("appleAccountToken"),
    )
}

internal fun parseLarktunEntitlement(json: String?): LarktunEntitlement? {
    if (json.isNullOrBlank()) return null
    val obj = JSONObject(json)
    return parseLarktunEntitlement(obj)
}

internal fun parseLarktunEntitlement(obj: JSONObject?): LarktunEntitlement? {
    if (obj == null) return null
    return LarktunEntitlement(
        source = obj.optStringOrNull("source"),
        planCode = obj.optStringOrNull("planCode"),
        status = obj.optStringOrNull("status"),
        deviceLimit = obj.optIntOrNull("deviceLimit"),
        deviceCount = obj.optIntOrNull("deviceCount"),
        dedicatedDerperEnabled = obj.optBooleanOrNull("dedicatedDerperEnabled"),
        expiresAt = obj.optStringOrNull("expiresAt"),
    )
}

internal fun JSONObject.putNullable(name: String, value: Any?) {
    if (value == null) {
        put(name, JSONObject.NULL)
    } else {
        put(name, value)
    }
}

internal fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).trim().takeIf { it.isNotEmpty() }
}

internal fun JSONObject.optIntOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name)
}

internal fun JSONObject.optLongOrNull(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return optLong(name)
}

internal fun JSONObject.optBooleanOrNull(name: String): Boolean? {
    if (!has(name) || isNull(name)) return null
    return optBoolean(name)
}
