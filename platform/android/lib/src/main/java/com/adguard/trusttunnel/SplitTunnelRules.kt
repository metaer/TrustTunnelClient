package com.adguard.trusttunnel

/**
 * Resolves `split_tunnel_mode`/`split_tunnel_apps` into the exact package lists to feed to
 * `VpnService.Builder`. Pure Kotlin (no Android dependencies) so it is unit-testable on the JVM.
 *
 * Every listed package is passed to the Builder: the platform resolves the names itself, ignores
 * unknown ones and is not limited by this app's package visibility. Whether a package is installed
 * and visible to this app only decides the `include` fallback and what is reported.
 *
 * Invariants of every [Result]:
 *  - at most one of [Result.allowedPackages] / [Result.disallowedPackages] is non-empty
 *    (Android forbids mixing `addAllowedApplication` and `addDisallowedApplication`);
 *  - `selfPackage` is never in [Result.allowedPackages] and is always the first entry of a
 *    non-empty [Result.disallowedPackages];
 *  - `effectiveMode == INCLUDE` implies that at least one of [Result.allowedPackages] is installed.
 */
internal object SplitTunnelRules {

    enum class FallbackReason {
        /** `include` requested but the list was empty (or contained only blanks / this app). */
        EMPTY_LIST,

        /** `include` requested but none of the listed apps is installed / visible. */
        NOTHING_RESOLVED,
    }

    data class Result(
        /** Mode parsed from the wire value; `OFF` when unknown (see [unknownModeWire]). */
        val requestedMode: SplitTunnelMode,
        /** Mode actually applied. Differs from [requestedMode] only when [fallbackReason] != null. */
        val effectiveMode: SplitTunnelMode,
        /** Arguments for `addAllowedApplication` (INCLUDE only). */
        val allowedPackages: List<String>,
        /** Arguments for `addDisallowedApplication` (OFF / EXCLUDE). Self first. */
        val disallowedPackages: List<String>,
        /**
         * Listed packages that are not installed or not visible to this app. They are still passed
         * to the Builder (when the mode applies), which ignores packages that are not installed.
         */
        val unresolved: List<String>,
        /** Listed entries skipped because they were blank, duplicates, or this app itself. */
        val ignored: List<String>,
        /** Raw wire value when it was not recognised (treated as `off`). */
        val unknownModeWire: String?,
        /** Set when INCLUDE collapsed to the full tunnel. */
        val fallbackReason: FallbackReason?,
    )

    /** Rules equivalent to the historical behaviour: everything is tunnelled except this app. */
    fun fullTunnel(selfPackage: String, unknownModeWire: String? = null): Result = Result(
        requestedMode = SplitTunnelMode.OFF,
        effectiveMode = SplitTunnelMode.OFF,
        allowedPackages = emptyList(),
        disallowedPackages = listOf(selfPackage),
        unresolved = emptyList(),
        ignored = emptyList(),
        unknownModeWire = unknownModeWire,
        fallbackReason = null,
    )

    /**
     * Resolves the rules for the wire mode [modeWire] and the package list [apps].
     *
     * @param selfPackage package name of this app, which never goes through its own tunnel
     * @param isInstalled whether a package is installed and visible to this app;
     *                    only consulted for `include` and `exclude`
     */
    fun resolve(
        modeWire: String,
        apps: List<String>,
        selfPackage: String,
        isInstalled: (String) -> Boolean,
    ): Result {
        val requested = SplitTunnelMode.fromWireOrNull(modeWire)
            ?: return fullTunnel(selfPackage, unknownModeWire = modeWire)
        if (requested == SplitTunnelMode.OFF) {
            // The list is intentionally not inspected: the UI keeps it while the mode is `off`.
            return fullTunnel(selfPackage)
        }

        val (candidates, ignored) = normalize(apps, selfPackage)
        val unresolved = candidates.filterNot(isInstalled)
        if (requested == SplitTunnelMode.EXCLUDE) {
            return Result(
                requestedMode = requested,
                effectiveMode = requested,
                allowedPackages = emptyList(),
                disallowedPackages = listOf(selfPackage) + candidates,
                unresolved = unresolved,
                ignored = ignored,
                unknownModeWire = null,
                fallbackReason = null,
            )
        }

        // INCLUDE. Allowed packages that resolve to no installed app leave the tunnel without any
        // app, so a list without a single installed app falls back to the full tunnel instead.
        if (unresolved.size == candidates.size) {
            return fullTunnel(selfPackage).copy(
                requestedMode = requested,
                unresolved = unresolved,
                ignored = ignored,
                fallbackReason = if (candidates.isEmpty()) FallbackReason.EMPTY_LIST else FallbackReason.NOTHING_RESOLVED,
            )
        }
        return Result(
            requestedMode = requested,
            effectiveMode = requested,
            allowedPackages = candidates,
            disallowedPackages = emptyList(),
            unresolved = unresolved,
            ignored = ignored,
            unknownModeWire = null,
            fallbackReason = null,
        )
    }

    /**
     * Trims [apps] and splits them into the packages to use (first occurrence wins, order kept)
     * and the ignored entries: blanks, duplicates and [selfPackage].
     */
    private fun normalize(apps: List<String>, selfPackage: String): Pair<List<String>, List<String>> {
        val kept = LinkedHashSet<String>()
        val ignored = ArrayList<String>()
        for (raw in apps) {
            val pkg = raw.trim()
            when {
                pkg.isEmpty() -> ignored += raw
                pkg == selfPackage -> ignored += pkg
                pkg in kept -> ignored += pkg
                else -> kept += pkg
            }
        }
        return kept.toList() to ignored
    }
}
