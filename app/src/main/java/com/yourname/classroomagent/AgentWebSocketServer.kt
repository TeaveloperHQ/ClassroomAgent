package com.yourname.classroomagent

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

class AgentWebSocketServer(
    port: Int,
    private val context: Context,
    private val onCommand: (String, org.java_websocket.WebSocket) -> Unit
) : WebSocketServer(InetSocketAddress("0.0.0.0", port)) {

    companion object {
        var editRequested = false
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        android.util.Log.d("WebSocket", "선생님 연결됨: ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        android.util.Log.d("WebSocket", "연결 종료됨")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val trimmed = message.trim()
        android.util.Log.d("WebSocket", "명령 수신: $trimmed")

        val command = trimmed

        if (command == "STATUS") {
            val sessionStatus = if (ClassWatcherService.isClassInSession) "IN_SESSION" else "IDLE"
            val accessibilityEnabled = isAccessibilityEnabled()
            val overlayEnabled = Settings.canDrawOverlays(context)
            conn.send("$sessionStatus|ACCESSIBILITY:$accessibilityEnabled|OVERLAY:$overlayEnabled|EDIT_REQUEST:$editRequested")
            return
        }

        if (command == "EDIT_APPROVED" || command == "EDIT_REJECTED") {
            editRequested = false
        }

        onCommand(command, conn)
        conn.send("OK: $command")
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        android.util.Log.e("WebSocket", "에러: ${ex.message}")
    }

    override fun onStart() {
        android.util.Log.d("WebSocket", "서버 시작됨 - 주소: ${this.address}")
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }
}
