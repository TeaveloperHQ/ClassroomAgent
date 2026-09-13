package com.teaveloper.classroomagent

import java.util.concurrent.ConcurrentHashMap

/**
 * Sliding-window aggregation of "which peers used which app recently".
 *
 * A single peer counts once per app regardless of how many switches it made
 * (we care about distinct users, not raw event volume). When an app crosses
 * the consensus threshold — a share of the currently-discovered peer roster —
 * it is auto-promoted into ClassWatcherService.allowedPackages.
 *
 * "Peers" here includes ourselves: our own usage counts too, so a class where
 * everyone naturally uses app X converges to allowing X without any teacher
 * involvement.
 */
object UsageAggregator {

    private const val WINDOW_MS = 10 * 60 * 1000L        // 10 minutes
    private const val THRESHOLD_RATIO = 0.30              // 30% of roster
    private const val MIN_PEERS_FOR_PROMOTION = 3         // absolute floor: never promote on < 3

    /** pkg → (peerName → lastSeenEpochMs). One entry per distinct peer. */
    private val observations = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    fun record(pkg: String, peerName: String, epochMs: Long) {
        if (pkg.isBlank() || peerName.isBlank()) return
        observations.getOrPut(pkg) { ConcurrentHashMap() }[peerName] = epochMs
        maybePromote(pkg)
    }

    private fun maybePromote(pkg: String) {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        val active = observations[pkg]?.count { it.value >= cutoff } ?: 0
        // roster = discovered peers + self
        val rosterSize = PeerRegistry.size() + 1
        val ratioThreshold = (rosterSize * THRESHOLD_RATIO).toInt().coerceAtLeast(1)
        val threshold = maxOf(MIN_PEERS_FOR_PROMOTION, ratioThreshold)
        if (active >= threshold && pkg !in ClassWatcherService.allowedPackages) {
            ClassWatcherService.allowedPackages =
                (ClassWatcherService.allowedPackages + pkg).toMutableSet()
            android.util.Log.d(
                "Aggregator",
                "$pkg 합의로 승격 ($active/$rosterSize peers, threshold=$threshold)"
            )
        }
    }

    /** Snapshot for debugging / status reporting. */
    fun snapshot(): Map<String, Int> {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        return observations.mapValues { (_, peers) -> peers.count { it.value >= cutoff } }
    }

    fun clear() {
        observations.clear()
    }
}
