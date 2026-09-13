package com.teaveloper.classroomagent

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
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
            val suspiciousPkg = ClassWatcherService.suspiciousPackage
            val suspiciousField = if (suspiciousPkg != null)
                "$suspiciousPkg:${ClassWatcherService.suspiciousTime ?: ""}"
            else "none"
            val vpnStatus = LocalVpnService.isRunning
            conn.send("$sessionStatus|ACCESSIBILITY:$accessibilityEnabled|OVERLAY:$overlayEnabled|EDIT_REQUEST:$editRequested|SUSPICIOUS:$suspiciousField|VPN:$vpnStatus")
            return
        }

        if (command == "GET_APPS") {
            try {
                val allApps = context.packageManager.getInstalledApplications(0)
                val teamsApp = allApps.find { it.packageName.contains("teams", ignoreCase = true)
                    || it.packageName.contains("microsoft", ignoreCase = true) }
                android.util.Log.d("GET_APPS", "Teams 검색 결과: ${teamsApp?.packageName ?: "없음"}")

                val teamsLaunch = teamsApp?.let {
                    context.packageManager.getLaunchIntentForPackage(it.packageName)
                }
                android.util.Log.d("GET_APPS", "Teams launch intent: $teamsLaunch")

                val pm = context.packageManager
                val method1 = pm.getInstalledApplications(0)
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .map { "${pm.getApplicationLabel(it)}:${it.packageName}" }

                val launcherIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
                val method2 = pm.queryIntentActivities(launcherIntent, 0)
                    .map { "${it.loadLabel(pm)}:${it.activityInfo.packageName}" }

                val result = (method1 + method2)
                    .distinctBy { it.substringAfter(":") }
                    .sortedBy { it.substringBefore(":") }
                android.util.Log.d("GET_APPS", "Method1: ${method1.size}, Method2: ${method2.size}, 합계: ${result.size}")
                val response = "APP_LIST|" + result.joinToString("|")
                conn.send(response)
            } catch (e: Exception) {
                android.util.Log.e("GET_APPS", "오류 발생: ${e.message}", e)
                conn.send("APP_LIST|")
            }
            return
        }

        if (command.startsWith("SET_ALLOWED_SITES")) {
            android.util.Log.d("VpnService", "SET_ALLOWED_SITES 수신: $command")
            val parts = command.split("|")
            val domains = parts.drop(1).toMutableSet()
            android.util.Log.d("VpnService", "도메인 목록: $domains")
            LocalVpnService.updateAllowedDomains(domains)
            context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
                .edit()
                .putStringSet("allowed_domains", domains)
                .apply()
            android.util.Log.d("VpnService", "allowedDomains 업데이트 완료: ${LocalVpnService.allowedDomains}")
            conn.send("OK|SET_ALLOWED_SITES")
            return
        }

        if (command == "START") {
            val prefs = context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
            LocalVpnService.allowedDomains = prefs
                .getStringSet("allowed_domains", mutableSetOf())!!
                .toMutableSet()
            if (VpnService.prepare(context) == null) {
                context.startService(
                    Intent(context, LocalVpnService::class.java)
                        .setAction(LocalVpnService.ACTION_START)
                )
            } else {
                android.util.Log.w("ClassroomAgent", "VPN permission not yet granted")
            }
        }

        if (command == "STOP") {
            context.startService(
                Intent(context, LocalVpnService::class.java)
                    .setAction(LocalVpnService.ACTION_STOP)
            )
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
