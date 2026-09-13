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

    /**
     * Apps that this agent added to allowedPackages via consensus (not via
     * DEFAULT_ALLOWED_PACKAGES or teacher SET_ALLOWED_APPS). Only entries here
     * are subject to decay-based demotion — teacher/default entries are never
     * removed automatically.
     */
    private val consensusPromoted = ConcurrentHashMap.newKeySet<String>()

    fun record(pkg: String, peerName: String, epochMs: Long) {
        if (pkg.isBlank() || peerName.isBlank()) return
        observations.getOrPut(pkg) { ConcurrentHashMap() }[peerName] = epochMs
        maybePromote(pkg)
        sweep()
    }

    /**
     * Called when teacher pushes a fresh allowlist. Consensus additions are
     * dropped so the teacher's list is authoritative; consensus can re-promote
     * anything not on the teacher's list on the next observation.
     */
    fun forgetConsensusPromotions() {
        consensusPromoted.clear()
    }

    fun currentThreshold(): Int {
        // Roster = all discovered peers + self. School-wide observation
        // strengthens the signal ("what is legitimate educational activity")
        // and only flags anomalies — the point isn't class-specific consensus,
        // it's catching outliers against the whole cooperative baseline.
        val rosterSize = PeerRegistry.size() + 1
        val ratioThreshold = (rosterSize * THRESHOLD_RATIO).toInt().coerceAtLeast(1)
        return maxOf(MIN_PEERS_FOR_PROMOTION, ratioThreshold)
    }

    fun activeCount(pkg: String): Int {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        return observations[pkg]?.count { it.value >= cutoff } ?: 0
    }

    private fun maybePromote(pkg: String) {
        val active = activeCount(pkg)
        val threshold = currentThreshold()
        if (active >= threshold && pkg !in ClassWatcherService.allowedPackages) {
            ClassWatcherService.allowedPackages =
                (ClassWatcherService.allowedPackages + pkg).toMutableSet()
            consensusPromoted += pkg
            android.util.Log.d(
                "Aggregator",
                "$pkg 합의로 승격 ($active/${PeerRegistry.size() + 1} peers, threshold=$threshold)"
            )
            EventLog.record("PROMOTE", "$pkg ($active/$threshold)")
        }
    }

    /**
     * Demote consensus-promoted apps whose distinct-peer count fell below the
     * threshold. Teacher-mandated / default apps are never touched — only
     * entries this agent itself promoted are eligible.
     */
    private fun sweep() {
        val threshold = currentThreshold()
        val toRemove = consensusPromoted.filter { activeCount(it) < threshold }
        if (toRemove.isEmpty()) return
        for (pkg in toRemove) {
            consensusPromoted.remove(pkg)
            ClassWatcherService.allowedPackages =
                (ClassWatcherService.allowedPackages - pkg).toMutableSet()
            android.util.Log.d("Aggregator", "$pkg 합의 만료로 강등")
            EventLog.record("DEMOTE", pkg)
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
