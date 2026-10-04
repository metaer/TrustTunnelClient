package com.adguard.trusttunnel

import com.adguard.trusttunnel.SplitTunnelRules.FallbackReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SplitTunnelRulesTest {
    private val neverCalled: (String) -> Boolean = {
        fail("isInstalled must not be consulted, was called for '$it'")
        false
    }

    private fun installedOnly(vararg packages: String): (String) -> Boolean = { it in packages }

    @Test
    fun off_disallowsOnlySelf_andIgnoresList() {
        val rules = SplitTunnelRules.resolve("off", listOf("a", "b"), SELF, neverCalled)

        assertEquals(SplitTunnelMode.OFF, rules.requestedMode)
        assertEquals(SplitTunnelMode.OFF, rules.effectiveMode)
        assertEquals(emptyList<String>(), rules.allowedPackages)
        assertEquals(listOf(SELF), rules.disallowedPackages)
        assertEquals(emptyList<String>(), rules.unresolved)
        assertEquals(emptyList<String>(), rules.ignored)
        assertNull(rules.unknownModeWire)
        assertNull(rules.fallbackReason)
    }

    @Test
    fun unknownWire_isTreatedAsOff_andReported() {
        val rules = SplitTunnelRules.resolve("whatever", listOf("a"), SELF, neverCalled)

        assertEquals(SplitTunnelMode.OFF, rules.requestedMode)
        assertEquals(SplitTunnelMode.OFF, rules.effectiveMode)
        assertEquals(emptyList<String>(), rules.allowedPackages)
        assertEquals(listOf(SELF), rules.disallowedPackages)
        assertEquals("whatever", rules.unknownModeWire)
        assertNull(rules.fallbackReason)

        assertEquals("", SplitTunnelRules.resolve("", listOf("a"), SELF, neverCalled).unknownModeWire)
    }

    @Test
    fun wire_isTrimmedAndCaseInsensitive() {
        val include = SplitTunnelRules.resolve(" Include ", listOf("a"), SELF, installedOnly("a"))
        assertEquals(SplitTunnelMode.INCLUDE, include.requestedMode)
        assertEquals(SplitTunnelMode.INCLUDE, include.effectiveMode)
        assertEquals(listOf("a"), include.allowedPackages)
        assertNull(include.unknownModeWire)

        val exclude = SplitTunnelRules.resolve("EXCLUDE", listOf("a"), SELF, installedOnly("a"))
        assertEquals(SplitTunnelMode.EXCLUDE, exclude.effectiveMode)
        assertEquals(listOf(SELF, "a"), exclude.disallowedPackages)
        assertNull(exclude.unknownModeWire)

        val off = SplitTunnelRules.resolve("\tOff\n", listOf("a"), SELF, neverCalled)
        assertEquals(SplitTunnelMode.OFF, off.effectiveMode)
        assertNull(off.unknownModeWire)
    }

    @Test
    fun splitTunnelMode_fromWire_mapsKnownValuesAndFallsBackToOff() {
        for (mode in SplitTunnelMode.entries) {
            assertEquals(mode, SplitTunnelMode.fromWireOrNull(mode.wire))
            assertEquals(mode, SplitTunnelMode.fromWire(mode.wire.uppercase()))
        }
        assertNull(SplitTunnelMode.fromWireOrNull("bypass"))
        assertNull(SplitTunnelMode.fromWireOrNull(""))
        assertEquals(SplitTunnelMode.OFF, SplitTunnelMode.fromWire("bypass"))
    }

    @Test
    fun include_allowsEveryListedApp_reportsUnresolved_skipsSelf() {
        val rules = SplitTunnelRules.resolve("include", listOf(SELF, "a", "b", "c"), SELF, installedOnly("a", "c"))

        assertEquals(SplitTunnelMode.INCLUDE, rules.requestedMode)
        assertEquals(SplitTunnelMode.INCLUDE, rules.effectiveMode)
        // "b" is not visible to this app, but the platform resolves it itself if it is installed.
        assertEquals(listOf("a", "b", "c"), rules.allowedPackages)
        assertEquals(emptyList<String>(), rules.disallowedPackages)
        assertEquals(listOf("b"), rules.unresolved)
        assertEquals(listOf(SELF), rules.ignored)
        assertNull(rules.unknownModeWire)
        assertNull(rules.fallbackReason)
    }

    @Test
    fun include_unresolvedAppsAreReported() {
        val rules = SplitTunnelRules.resolve("include", listOf("a", "missing"), SELF, installedOnly("a"))

        assertEquals(SplitTunnelMode.INCLUDE, rules.effectiveMode)
        assertEquals(listOf("a", "missing"), rules.allowedPackages)
        assertEquals(listOf("missing"), rules.unresolved)
        assertNull(rules.fallbackReason)
    }

    @Test
    fun include_emptyList_fallsBackToFullTunnel() {
        val rules = SplitTunnelRules.resolve("include", emptyList(), SELF, neverCalled)

        assertEquals(SplitTunnelMode.INCLUDE, rules.requestedMode)
        assertEquals(SplitTunnelMode.OFF, rules.effectiveMode)
        assertEquals(emptyList<String>(), rules.allowedPackages)
        assertEquals(listOf(SELF), rules.disallowedPackages)
        assertEquals(emptyList<String>(), rules.unresolved)
        assertEquals(emptyList<String>(), rules.ignored)
        assertEquals(FallbackReason.EMPTY_LIST, rules.fallbackReason)
    }

    @Test
    fun include_onlySelfOrBlanks_fallsBackToFullTunnel() {
        val rules = SplitTunnelRules.resolve("include", listOf(SELF, "", "  ", " $SELF "), SELF, neverCalled)

        assertEquals(SplitTunnelMode.INCLUDE, rules.requestedMode)
        assertEquals(SplitTunnelMode.OFF, rules.effectiveMode)
        assertEquals(emptyList<String>(), rules.allowedPackages)
        assertEquals(listOf(SELF), rules.disallowedPackages)
        assertEquals(listOf(SELF, "", "  ", SELF), rules.ignored)
        assertEquals(FallbackReason.EMPTY_LIST, rules.fallbackReason)
    }

    @Test
    fun include_nothingInstalled_fallsBackToFullTunnel() {
        val rules = SplitTunnelRules.resolve("include", listOf("a", "b"), SELF, installedOnly())

        assertEquals(SplitTunnelMode.INCLUDE, rules.requestedMode)
        assertEquals(SplitTunnelMode.OFF, rules.effectiveMode)
        assertEquals(emptyList<String>(), rules.allowedPackages)
        assertEquals(listOf(SELF), rules.disallowedPackages)
        assertEquals(listOf("a", "b"), rules.unresolved)
        assertEquals(emptyList<String>(), rules.ignored)
        assertEquals(FallbackReason.NOTHING_RESOLVED, rules.fallbackReason)
    }

    @Test
    fun include_deduplicatesPreservingOrder() {
        val rules = SplitTunnelRules.resolve("include", listOf("b", "a", "b", " a "), SELF, installedOnly("a", "b"))

        assertEquals(SplitTunnelMode.INCLUDE, rules.effectiveMode)
        assertEquals(listOf("b", "a"), rules.allowedPackages)
        assertEquals(listOf("b", "a"), rules.ignored)
        assertEquals(emptyList<String>(), rules.unresolved)
    }

    @Test
    fun exclude_disallowsSelfFirstThenEveryListedApp_reportsUnresolved() {
        val rules = SplitTunnelRules.resolve("exclude", listOf("a", "b", "c"), SELF, installedOnly("a", "c"))

        assertEquals(SplitTunnelMode.EXCLUDE, rules.requestedMode)
        assertEquals(SplitTunnelMode.EXCLUDE, rules.effectiveMode)
        assertEquals(emptyList<String>(), rules.allowedPackages)
        assertEquals(listOf(SELF, "a", "b", "c"), rules.disallowedPackages)
        assertEquals(listOf("b"), rules.unresolved)
        assertEquals(emptyList<String>(), rules.ignored)
        assertNull(rules.fallbackReason)
    }

    @Test
    fun exclude_emptyList_disallowsOnlySelf_noFallback() {
        val rules = SplitTunnelRules.resolve("exclude", emptyList(), SELF, neverCalled)

        assertEquals(SplitTunnelMode.EXCLUDE, rules.requestedMode)
        assertEquals(SplitTunnelMode.EXCLUDE, rules.effectiveMode)
        assertEquals(emptyList<String>(), rules.allowedPackages)
        assertEquals(listOf(SELF), rules.disallowedPackages)
        assertNull(rules.fallbackReason)
    }

    @Test
    fun exclude_selfInList_isNotDuplicated() {
        val rules = SplitTunnelRules.resolve("exclude", listOf(SELF, "a", SELF), SELF, installedOnly(SELF, "a"))

        assertEquals(listOf(SELF, "a"), rules.disallowedPackages)
        assertEquals(listOf(SELF, SELF), rules.ignored)
        assertEquals(emptyList<String>(), rules.unresolved)
    }

    @Test
    fun fullTunnel_matchesOffResolution() {
        val fullTunnel = SplitTunnelRules.fullTunnel(SELF)

        assertEquals(fullTunnel, SplitTunnelRules.resolve("off", emptyList(), SELF, neverCalled))
        assertEquals(fullTunnel, SplitTunnelRules.resolve("off", listOf("a", SELF), SELF, neverCalled))
    }

    @Test
    fun invariant_neverBothListsNonEmpty() {
        val modes = listOf("off", "include", "exclude", "INCLUDE", "bogus", "")
        val appLists = listOf(
            emptyList(),
            listOf(SELF),
            listOf("a"),
            listOf("a", "b"),
            listOf("", " a ", "a", SELF),
            listOf("missing"),
        )
        val installedPredicates = listOf(installedOnly(), installedOnly("a"), installedOnly("a", "b", "missing", SELF))

        for (mode in modes) {
            for (apps in appLists) {
                for (isInstalled in installedPredicates) {
                    val rules = SplitTunnelRules.resolve(mode, apps, SELF, isInstalled)
                    val context = "mode='$mode' apps=$apps -> $rules"

                    assertFalse(context, rules.allowedPackages.isNotEmpty() && rules.disallowedPackages.isNotEmpty())
                    assertFalse(context, SELF in rules.allowedPackages)
                    if (rules.effectiveMode == SplitTunnelMode.INCLUDE) {
                        assertTrue(context, rules.allowedPackages.any(isInstalled))
                    } else {
                        // This app never goes through its own tunnel.
                        assertEquals(context, SELF, rules.disallowedPackages.firstOrNull())
                    }
                    assertEquals(context, rules.allowedPackages.distinct(), rules.allowedPackages)
                    assertEquals(context, rules.disallowedPackages.distinct(), rules.disallowedPackages)
                    assertEquals(context, rules.fallbackReason != null, rules.effectiveMode != rules.requestedMode)
                }
            }
        }
    }

    companion object {
        private const val SELF = "com.adguard.trusttunnel.split"
    }
}
