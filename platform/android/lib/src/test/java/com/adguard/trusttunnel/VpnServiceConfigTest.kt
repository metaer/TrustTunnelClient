package com.adguard.trusttunnel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VpnServiceConfigTest {
    private val logged = mutableListOf<String>()

    @Before
    fun setUp() {
        Logger.setCallback(Logger.Callback { _, message -> logged += message })
    }

    @After
    fun tearDown() {
        Logger.setCallback(null)
    }

    @Test
    fun parse_withoutSplitTunnelKeys_usesDefaults() {
        val config = parse(BASE_TUN)
        val tun = config.listener.tun

        assertEquals("off", tun.splitTunnelMode)
        assertEquals(SplitTunnelMode.OFF, SplitTunnelMode.fromWire(tun.splitTunnelMode))
        assertEquals(emptyList<String>(), tun.splitTunnelApps)
        assertEquals(listOf("0.0.0.0/0", "2000::/3"), tun.includedRoutes)
        assertEquals(emptyList<String>(), tun.excludedRoutes)
        assertEquals(1350L, tun.mtuSize)
        assertEquals(emptyList<String>(), config.endpoint.dnsUpstreams)
    }

    @Test
    fun parse_includeMode_readsModeAndApps() {
        val tun = parse(
            BASE_TUN + listOf(
                "split_tunnel_mode = \"include\"",
                "split_tunnel_apps = [\"com.android.chrome\", \"org.mozilla.firefox\"]",
            )
        ).listener.tun

        assertEquals("include", tun.splitTunnelMode)
        assertEquals(SplitTunnelMode.INCLUDE, SplitTunnelMode.fromWire(tun.splitTunnelMode))
        assertEquals(listOf("com.android.chrome", "org.mozilla.firefox"), tun.splitTunnelApps)
    }

    @Test
    fun parse_excludeMode_readsModeAndApps() {
        val tun = parse(
            BASE_TUN + listOf(
                "split_tunnel_mode = \"exclude\"",
                "split_tunnel_apps = [\"com.google.android.youtube\"]",
            )
        ).listener.tun

        assertEquals("exclude", tun.splitTunnelMode)
        assertEquals(SplitTunnelMode.EXCLUDE, SplitTunnelMode.fromWire(tun.splitTunnelMode))
        assertEquals(listOf("com.google.android.youtube"), tun.splitTunnelApps)
    }

    @Test
    fun parse_emptyAppsArray_isAccepted() {
        val tun = parse(BASE_TUN + listOf("split_tunnel_mode = \"include\"", "split_tunnel_apps = []")).listener.tun

        assertEquals("include", tun.splitTunnelMode)
        assertEquals(emptyList<String>(), tun.splitTunnelApps)
    }

    @Test
    fun parse_unknownModeString_isKeptVerbatim_andMapsToOff() {
        val tun = parse(
            BASE_TUN + listOf("split_tunnel_mode = \"weird\"", "split_tunnel_apps = [\"com.android.chrome\"]")
        ).listener.tun

        assertEquals("weird", tun.splitTunnelMode)
        assertEquals(listOf("com.android.chrome"), tun.splitTunnelApps)
        assertNull(SplitTunnelMode.fromWireOrNull(tun.splitTunnelMode))
        assertEquals(SplitTunnelMode.OFF, SplitTunnelMode.fromWire(tun.splitTunnelMode))

        val rules = SplitTunnelRules.resolve(tun.splitTunnelMode, tun.splitTunnelApps, SELF) { true }
        assertEquals(SplitTunnelMode.OFF, rules.effectiveMode)
        assertEquals("weird", rules.unknownModeWire)
        assertEquals(listOf(SELF), rules.disallowedPackages)
    }

    @Test
    fun parse_unknownTunKeys_areStillIgnored() {
        val tun = parse(
            BASE_TUN + listOf(
                "split_tunnel_mode = \"exclude\"",
                "split_tunnel_apps = [\"com.android.chrome\"]",
                "device_name = \"tun0\"",
                "tcp_recv_buf_size = 0",
                "change_system_dns = true",
            )
        ).listener.tun

        assertEquals("exclude", tun.splitTunnelMode)
        assertEquals(listOf("com.android.chrome"), tun.splitTunnelApps)
        assertEquals(1350L, tun.mtuSize)
    }

    @Test
    fun parse_missingRequiredKey_returnsNullAndLogs() {
        val withoutMtu = BASE_TUN.filterNot { it.startsWith("mtu_size") } +
            listOf("split_tunnel_mode = \"include\"", "split_tunnel_apps = [\"com.android.chrome\"]")

        assertNull(VpnServiceConfig.parseToml(document(withoutMtu)))
        assertTrue("logged: $logged", logged.any { it.contains("Failed to parse config") })
    }

    @Test
    fun parseLogLevel_readsTheTopLevelValue() {
        assertEquals("error", VpnServiceConfig.parseLogLevel(document(BASE_TUN, listOf("loglevel = \"error\""))))
        assertEquals("info", VpnServiceConfig.parseLogLevel(document(BASE_TUN)))
    }

    @Test
    fun parseLogLevel_isNullWhenMissing() {
        assertNull(VpnServiceConfig.parseLogLevel(document(BASE_TUN, topLevelLines = emptyList())))
    }

    @Test
    fun parseLogLevel_ignoresNonStringValues_andTheConfigStillParses() {
        for (line in listOf("loglevel = 1", "loglevel = true", "loglevel = [\"debug\"]")) {
            val document = document(BASE_TUN, listOf(line))

            assertNull(line, VpnServiceConfig.parseLogLevel(document))
            assertNotNull(line, VpnServiceConfig.parseToml(document))
        }
    }

    private fun parse(
        tunLines: List<String>,
        topLevelLines: List<String> = DEFAULT_TOP_LEVEL,
    ): VpnServiceConfig {
        val config = VpnServiceConfig.parseToml(document(tunLines, topLevelLines))
        assertNotNull("Config should parse, logged: $logged", config)
        assertEquals(emptyList<String>(), logged)
        return config!!
    }

    /**
     * Mirrors the layout of the document the Flutter app's ConfigurationEncoder emits (keys this
     * parser does not model and the empty `[listener]` header included), with [tunLines] as the
     * body of `[listener.tun]` and [topLevelLines] before the remaining top-level keys.
     */
    private fun document(
        tunLines: List<String>,
        topLevelLines: List<String> = DEFAULT_TOP_LEVEL,
    ): String = (
        topLevelLines + listOf(
            "vpn_mode = \"general\"",
            "killswitch_enabled = true",
            "exclusions = []",
            "",
            "[endpoint]",
            "hostname = \"vpn.example.org\"",
            "addresses = [\"192.0.2.1:443\"]",
            "has_ipv6 = true",
            "username = \"user\"",
            "password = \"secret\"",
            "upstream_protocol = \"http2\"",
            "dns_upstreams = []",
            "",
            "[listener]",
            "",
            "[listener.tun]",
        ) + tunLines
    ).joinToString("\n")

    companion object {
        private const val SELF = "com.adguard.trusttunnel.split"

        private val DEFAULT_TOP_LEVEL = listOf("loglevel = \"info\"")

        private val BASE_TUN = listOf(
            "included_routes = [\"0.0.0.0/0\", \"2000::/3\"]",
            "excluded_routes = []",
            "mtu_size = 1350",
        )
    }
}
