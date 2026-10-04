package com.adguard.trusttunnel

/** Per-app routing mode carried in `[listener.tun] split_tunnel_mode`. Android adapter only. */
enum class SplitTunnelMode(val wire: String) {
    /** Full tunnel: every app except this one (historical behaviour). */
    OFF("off"),

    /** Only the listed apps are routed through the tunnel. */
    INCLUDE("include"),

    /** Every app except this one and the listed apps is routed through the tunnel. */
    EXCLUDE("exclude");

    companion object {
        /** Strict mapping; `null` when [raw] is not a known wire value (trimmed, case-insensitive). */
        fun fromWireOrNull(raw: String): SplitTunnelMode? {
            val needle = raw.trim().lowercase()
            return entries.firstOrNull { it.wire == needle }
        }

        /** Lenient mapping: unknown values fall back to [OFF]. */
        fun fromWire(raw: String): SplitTunnelMode = fromWireOrNull(raw) ?: OFF
    }
}
