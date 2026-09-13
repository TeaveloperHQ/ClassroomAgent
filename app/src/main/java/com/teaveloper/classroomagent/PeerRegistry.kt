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
        val publicKeyB64: String?,
        val classId: String?,
        val lastSeenMs: Long
    )

    private val peers = ConcurrentHashMap<String, Peer>()

    fun upsert(name: String, host: String, port: Int, publicKeyB64: String?, classId: String?) {
        peers[name] = Peer(name, host, port, publicKeyB64, classId, System.currentTimeMillis())
    }

    fun remove(name: String) {
        peers.remove(name)
    }

    fun get(name: String): Peer? = peers[name]

    fun all(): List<Peer> = peers.values.toList()

    /** Peers whose classId matches the given one. Empty classId = no isolation, returns none. */
    fun inClass(classId: String): List<Peer> =
        if (classId.isBlank()) emptyList()
        else peers.values.filter { it.classId == classId }

    fun size(): Int = peers.size

    fun sizeInClass(classId: String): Int = inClass(classId).size

    fun clear() {
        peers.clear()
    }
}
