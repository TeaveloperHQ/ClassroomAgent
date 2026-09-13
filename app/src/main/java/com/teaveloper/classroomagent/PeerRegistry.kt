package com.teaveloper.classroomagent

import java.util.concurrent.ConcurrentHashMap

/**
 * Roster of nearby classroom agents discovered via mDNS.
 *
 * Peer consensus for the auto-allowlist depends on this: usage events are
 * gossiped to every peer in the registry, and threshold rules count how many
 * distinct peers reported each app.
 *
 * Keyed by mDNS serviceName (== deviceName set in setup). One entry per tablet.
 */
object PeerRegistry {

    data class Peer(
        val name: String,
        val host: String,
        val port: Int,
        val lastSeenMs: Long
    )

    private val peers = ConcurrentHashMap<String, Peer>()

    fun upsert(name: String, host: String, port: Int) {
        peers[name] = Peer(name, host, port, System.currentTimeMillis())
    }

    fun remove(name: String) {
        peers.remove(name)
    }

    fun all(): List<Peer> = peers.values.toList()

    fun size(): Int = peers.size

    fun clear() {
        peers.clear()
    }
}
