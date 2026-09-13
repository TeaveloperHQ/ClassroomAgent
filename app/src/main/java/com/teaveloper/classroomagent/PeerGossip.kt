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

    fun sendUsageEvent(pkg: String) {
        val me = PeerIdentity.myName
        if (me.isEmpty()) return
        val ts = System.currentTimeMillis()
        val msg = "P2P_USAGE|$pkg|$ts|$me"
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
