package com.adguard.trusttunnel

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class Tun (
    @SerialName("included_routes")
    val includedRoutes: List<String>,
    @SerialName("excluded_routes")
    val excludedRoutes: List<String>,
    @SerialName("mtu_size")
    val mtuSize: Long,
    /**
     * Per-app routing mode: `"off"` (default), `"include"` or `"exclude"`.
     * Kept as the raw wire string so that an unknown value degrades to `off`
     * (see [SplitTunnelMode.fromWire]) instead of failing the whole parse.
     */
    @SerialName("split_tunnel_mode")
    val splitTunnelMode: String = SplitTunnelMode.OFF.wire,
    /** Package names the mode applies to. Ignored when [splitTunnelMode] is `off`. */
    @SerialName("split_tunnel_apps")
    val splitTunnelApps: List<String> = emptyList(),
)

@Serializable
class Listener (
    val tun: Tun
)

@Serializable
class Endpoint (
    @SerialName("dns_upstreams")
    val dnsUpstreams: List<String> = emptyList()
)

@Serializable
class VpnServiceConfig (
    val listener: Listener,
    val endpoint: Endpoint
) {
    companion object {
        private val LOG = Logger("VpnServiceConfig")

        private val toml = Toml(
            inputConfig = TomlInputConfig(
                ignoreUnknownNames = true,
                allowEmptyValues = false,
                allowNullValues = true,
            )
        )

        fun parseToml(config: String): VpnServiceConfig? {
            try {
                return toml.decodeFromString(
                    serializer(),
                    config
                )
            } catch (e: Exception) {
                LOG.error("Failed to parse config: $e");
                return null;
            }
        }

        /**
         * Returns the top-level `loglevel` of [config], or `null` when it is absent, not a string or
         * the document cannot be parsed. Parsed separately so that it can never reject a configuration.
         */
        fun parseLogLevel(config: String): String? =
            runCatching { toml.decodeFromString(LogLevelOnly.serializer(), config).loglevel }.getOrNull()
    }
}

@Serializable
private class LogLevelOnly(
    val loglevel: String? = null,
)