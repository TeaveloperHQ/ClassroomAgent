package com.teaveloper.classroomagent

import java.util.concurrent.ConcurrentHashMap

/**
 * Distinct-peer count per domain, sliding-window.
 *
 * Same shape as UsageAggregator (apps), but URLs are treated as observation
 * data only for now — no auto-promotion into allowedDomains. Reasons:
 *
 *   1. DNS traffic is very high-volume and granular (dozens of subdomains
 *      per page). Threshold tuning needs real classroom data.
 *   2. Teacher-driven denial (SET_DENIED_SITES) covers the enforcement side.
 *   3. Once a stable threshold is chosen, promotion to allowedDomains is a
 *      one-line change in maybePromote() below.
 *
 * Snapshot is surfaced through the STATUS command so the teacher can inspect
 * class-wide domain patterns without polling anything else.
 */
object DomainUsageAggregator {

    private const val WINDOW_MS = 10 * 60 * 1000L

    private val observations = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    fun record(domain: String, peerName: String, epochMs: Long) {
        if (domain.isBlank() || peerName.isBlank()) return
        observations.getOrPut(domain) { ConcurrentHashMap() }[peerName] = epochMs
    }

    fun snapshot(): Map<String, Int> {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        return observations.mapValues { (_, peers) -> peers.count { it.value >= cutoff } }
    }

    fun clear() {
        observations.clear()
    }
}
