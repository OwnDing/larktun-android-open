package sh.haven.core.tunnel

import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

data class LarktunTailnetStatus(
    val phase: Phase,
    val backendState: String? = null,
    val hostname: String? = null,
    val selfDisplayName: String? = null,
    val selfAliasName: String? = null,
    val tailscaleIPs: List<String> = emptyList(),
    val selfDNSName: String? = null,
    val tailnetName: String? = null,
    val magicDNSSuffix: String? = null,
    val magicDNSEnabled: Boolean = false,
    val peerCount: Int = 0,
    val peers: List<LarktunTailnetPeer> = emptyList(),
    val lastError: String? = null,
    val updatedAt: String? = null,
) {
    enum class Phase {
        IDLE,
        STARTING,
        RUNNING,
        ERROR,
    }

    companion object {
        fun idle(): LarktunTailnetStatus = LarktunTailnetStatus(phase = Phase.IDLE)

        fun starting(): LarktunTailnetStatus = LarktunTailnetStatus(phase = Phase.STARTING)

        fun error(message: String): LarktunTailnetStatus =
            LarktunTailnetStatus(phase = Phase.ERROR, lastError = message)

        fun fromJson(json: String): LarktunTailnetStatus {
            val obj = JSONObject(json)
            val status = obj.optStringOrNull("status")?.lowercase()
            val lastError = obj.optStringOrNull("lastError")
            val phase = when {
                status == "running" -> Phase.RUNNING
                status == "starting" -> Phase.STARTING
                !lastError.isNullOrBlank() -> Phase.ERROR
                else -> Phase.IDLE
            }
            return LarktunTailnetStatus(
                phase = phase,
                backendState = obj.optStringOrNull("backendState"),
                hostname = obj.optStringOrNull("hostname"),
                selfDisplayName = obj.optStringOrNull("selfDisplayName"),
                selfAliasName = obj.optStringOrNull("selfAliasName"),
                tailscaleIPs = obj.optJSONArray("tailscaleIPs").toStringList(),
                selfDNSName = obj.optStringOrNull("selfDNSName"),
                tailnetName = obj.optStringOrNull("tailnetName"),
                magicDNSSuffix = obj.optStringOrNull("magicDNSSuffix"),
                magicDNSEnabled = obj.optBoolean("magicDNSEnabled", false),
                peerCount = obj.optInt("peerCount", 0),
                peers = obj.optJSONArray("peers").toPeerList(),
                lastError = lastError,
                updatedAt = obj.optStringOrNull("updatedAt"),
            )
        }
    }
}

data class LarktunTailnetPeer(
    val id: String,
    val publicKey: String? = null,
    val name: String? = null,
    val computedName: String? = null,
    val displayName: String? = null,
    val aliasName: String? = null,
    val hostName: String? = null,
    val dnsName: String? = null,
    val os: String? = null,
    val tailscaleIPs: List<String> = emptyList(),
    val online: Boolean = false,
    val active: Boolean = false,
    val exitNode: Boolean = false,
    val exitNodeOption: Boolean = false,
    val curAddr: String? = null,
    val lastSeen: String? = null,
    val lastHandshake: String? = null,
    val relay: String? = null,
    val peerRelay: String? = null,
    val keyExpiry: String? = null,
) {
    val bestName: String
        get() = firstNonBlank(
            aliasName,
            hostName,
            displayName,
            computedName,
            name,
            dnsName?.substringBefore('.'),
            tailscaleIPs.firstOrNull(),
            id,
        )
            ?: "Unknown device"

    val bestAddress: String?
        get() = firstNonBlank(dnsName, tailscaleIPs.firstOrNull())

    val primaryTailscaleIP: String?
        get() = tailscaleIPs.firstOrNull()
}

data class LarktunPingResult(
    val ip: String? = null,
    val nodeIP: String? = null,
    val nodeName: String? = null,
    val error: String? = null,
    val latencySeconds: Double? = null,
    val endpoint: String? = null,
    val peerRelay: String? = null,
    val derpRegionID: Int = 0,
    val derpRegionCode: String? = null,
    val peerAPIPort: Int = 0,
    val peerAPIURL: String? = null,
    val isLocalIP: Boolean = false,
) {
    fun toDisplayMessage(fallbackName: String): String {
        val target = firstNonBlank(nodeName, nodeIP, ip, fallbackName) ?: fallbackName
        if (!error.isNullOrBlank()) {
            return "Ping $target failed: $error"
        }

        val latency = latencySeconds?.let {
            "${(it * 1_000.0).roundToInt()} ms"
        } ?: "ok"
        val via = when {
            !peerRelay.isNullOrBlank() -> "peer-relay"
            derpRegionID != 0 -> firstNonBlank(derpRegionCode, "DERP") ?: "DERP"
            !endpoint.isNullOrBlank() -> endpoint
            else -> "TSMP"
        }
        return "Ping $target: $latency via $via"
    }

    companion object {
        fun fromJson(json: String): LarktunPingResult {
            val obj = JSONObject(json)
            return LarktunPingResult(
                ip = obj.optStringOrNull("ip"),
                nodeIP = obj.optStringOrNull("nodeIP"),
                nodeName = obj.optStringOrNull("nodeName"),
                error = obj.optStringOrNull("error"),
                latencySeconds = obj.optDoubleOrNull("latencySeconds"),
                endpoint = obj.optStringOrNull("endpoint"),
                peerRelay = obj.optStringOrNull("peerRelay"),
                derpRegionID = obj.optInt("derpRegionID", 0),
                derpRegionCode = obj.optStringOrNull("derpRegionCode"),
                peerAPIPort = obj.optInt("peerAPIPort", 0),
                peerAPIURL = obj.optStringOrNull("peerAPIURL"),
                isLocalIP = obj.optBoolean("isLocalIP", false),
            )
        }
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index, "").trim().takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
}

private fun JSONArray?.toPeerList(): List<LarktunTailnetPeer> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val obj = optJSONObject(index) ?: continue
            val id = firstNonBlank(
                obj.optStringOrNull("id"),
                obj.optStringOrNull("publicKey"),
                obj.optStringOrNull("dnsName"),
                obj.optStringOrNull("hostName"),
            ) ?: continue
            add(
                LarktunTailnetPeer(
                    id = id,
                    publicKey = obj.optStringOrNull("publicKey"),
                    name = obj.optStringOrNull("name"),
                    computedName = obj.optStringOrNull("computedName"),
                    displayName = obj.optStringOrNull("displayName"),
                    aliasName = obj.optStringOrNull("aliasName"),
                    hostName = obj.optStringOrNull("hostName"),
                    dnsName = obj.optStringOrNull("dnsName"),
                    os = obj.optStringOrNull("os"),
                    tailscaleIPs = obj.optJSONArray("tailscaleIPs").toStringList(),
                    online = obj.optBoolean("online", false),
                    active = obj.optBoolean("active", false),
                    exitNode = obj.optBoolean("exitNode", false),
                    exitNodeOption = obj.optBoolean("exitNodeOption", false),
                    curAddr = obj.optStringOrNull("curAddr"),
                    lastSeen = obj.optStringOrNull("lastSeen"),
                    lastHandshake = obj.optStringOrNull("lastHandshake"),
                    relay = obj.optStringOrNull("relay"),
                    peerRelay = obj.optStringOrNull("peerRelay"),
                    keyExpiry = obj.optStringOrNull("keyExpiry"),
                ),
            )
        }
    }
}

private fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).trim().takeIf { it.isNotEmpty() }
}

private fun JSONObject.optDoubleOrNull(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    return runCatching { optDouble(name) }
        .getOrNull()
        ?.takeIf { !it.isNaN() && !it.isInfinite() }
}

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()
