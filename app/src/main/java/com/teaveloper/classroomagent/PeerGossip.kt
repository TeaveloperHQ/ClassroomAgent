package com.teaveloper.classroomagent

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Fire-and-forget gossip of usage events to peer agents discovered via mDNS.
 *
 * Wire format (peer-directed, distinguishes from the teacher-directed USAGE broadcast):
 *   P2P_USAGE|<pkg>|<epochMs>|<senderName>
 *
 * Outbound WebSocket connections are cached per-peer and reopened on failure.
 * All send work runs on a single background executor so the accessibility
 * event handler stays fast.
 *
 * No cryptographic authentication here yet — added later as a hardening layer.
 * Sybil isn't a strong attack vector in a classroom (1 tablet ≙ 1 student).
 */
object PeerGossip {

    private val connections = ConcurrentHashMap<String, WebSocketClient>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PeerGossip").apply { isDaemon = true }
    }

    // Per-key last-sent timestamp for gossip rate limiting. DNS in particular
    // fires many queries per page load — we don't want to blast peers with them.
    private val lastGossipAt = ConcurrentHashMap<String, Long>()
    private const val DOMAIN_DEDUPE_MS = 30_000L

    fun sendUsageEvent(pkg: String) {
        val me = PeerIdentity.myName
        if (me.isEmpty()) return
        val ts = System.currentTimeMillis()
        val payload = "$pkg|$ts|$me"
        val sig = runCatching { PeerIdentity.signB64(payload.toByteArray()) }.getOrElse {
            android.util.Log.w("Gossip", "sign 실패: ${it.message}")
            return
        }
        broadcastToPeers("P2P_USAGE|$payload|$sig")
    }

    fun sendDomainEvent(domain: String) {
        val me = PeerIdentity.myName
        if (me.isEmpty() || domain.isBlank()) return
        val now = System.currentTimeMillis()
        val key = "d:$domain"
        val last = lastGossipAt[key] ?: 0L
        if (now - last < DOMAIN_DEDUPE_MS) return
        lastGossipAt[key] = now
        val payload = "$domain|$now|$me"
        val sig = runCatching { PeerIdentity.signB64(payload.toByteArray()) }.getOrElse {
            android.util.Log.w("Gossip", "sign 실패: ${it.message}")
            return
        }
        broadcastToPeers("P2P_DOMAIN|$payload|$sig")
    }

    private fun broadcastToPeers(msg: String) {
        // Gossip to every discovered agent, regardless of class. Cross-class
        // data is not contamination — it's more signal for what counts as
        // legitimate educational activity in this school. Class id survives
        // as metadata for DIAG but is not used for filtering.
        val peers = PeerRegistry.all()
        if (peers.isEmpty()) return
        executor.submit {
            for (peer in peers) {
                try {
                    val client = getOrCreateClient(peer) ?: continue
                    if (client.isOpen) client.send(msg)
                } catch (e: Exception) {
                    android.util.Log.w("Gossip", "send to ${peer.name} 실패: ${e.message}")
                    connections.remove(peer.name)
                }
            }
        }
    }

    private fun getOrCreateClient(peer: PeerRegistry.Peer): WebSocketClient? {
        connections[peer.name]?.let { if (it.isOpen) return it else connections.remove(peer.name) }
        return try {
            val uri = URI.create("ws://${peer.host}:${peer.port}")
            val peerName = peer.name
            val client = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {}
                override fun onMessage(message: String?) {}
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    PeerGossip.connections.remove(peerName)
                }
                override fun onError(ex: Exception?) {
                    PeerGossip.connections.remove(peerName)
                }
            }
            val opened = client.connectBlocking(2, TimeUnit.SECONDS)
            if (opened) {
                connections[peer.name] = client
                client
            } else null
        } catch (e: Exception) {
            android.util.Log.w("Gossip", "connect ${peer.name} 실패: ${e.message}")
            null
        }
    }

    fun shutdown() {
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
    }
}
