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
        @Volatile var instance: AgentWebSocketServer? = null
        private const val USAGE_DEDUPE_WINDOW_MS = 3000L
    }

    private var lastUsagePkg: String? = null
    private var lastUsageAt: Long = 0L

    override fun onStart() {
        instance = this
        android.util.Log.d("WebSocket", "서버 시작됨 - 주소: ${this.address}")
    }

    /**
     * Broadcast app usage observation to all connected teachers.
     * Same pkg within USAGE_DEDUPE_WINDOW_MS is skipped to avoid flooding when
     * accessibility fires TYPE_WINDOW_STATE_CHANGED repeatedly for the same app.
     */
    fun broadcastUsage(pkg: String) {
        val now = System.currentTimeMillis()
        if (pkg == lastUsagePkg && now - lastUsageAt < USAGE_DEDUPE_WINDOW_MS) return
        lastUsagePkg = pkg
        lastUsageAt = now
        try {
            broadcast("USAGE|$pkg|$now")
        } catch (e: Exception) {
            android.util.Log.w("WebSocket", "broadcastUsage 실패: ${e.message}")
        }
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
            val peerCount = PeerRegistry.size()
            val consensusSummary = UsageAggregator.snapshot()
                .entries
                .sortedByDescending { it.value }
                .take(5)
                .joinToString(",") { "${it.key}=${it.value}" }
                .ifEmpty { "none" }
            val domainSummary = DomainUsageAggregator.snapshot()
                .entries
                .sortedByDescending { it.value }
                .take(5)
                .joinToString(",") { "${it.key}=${it.value}" }
                .ifEmpty { "none" }
            conn.send(
                "$sessionStatus|ACCESSIBILITY:$accessibilityEnabled|OVERLAY:$overlayEnabled|" +
                "EDIT_REQUEST:$editRequested|SUSPICIOUS:$suspiciousField|VPN:$vpnStatus|" +
                "PEERS:$peerCount|CONSENSUS:$consensusSummary|DOMAINS:$domainSummary"
            )
            return
        }

        if (command == "DIAG") {
            // Full snapshot for on-site debugging via adb + wscat.
            // Not for normal teacher UI — STATUS covers that. Format is
            // multiline plain text, one field per line, easy to read.
            val sb = StringBuilder()
            sb.appendLine("== DIAG ==")
            sb.appendLine("session=${ClassWatcherService.isClassInSession}")
            sb.appendLine("myName=${PeerIdentity.myName}")
            sb.appendLine("peers=${PeerRegistry.size()}")
            PeerRegistry.all().sortedBy { it.name }.forEach {
                sb.appendLine("  peer ${it.name} ${it.host}:${it.port}")
            }
            sb.appendLine("allowedPackages(${ClassWatcherService.allowedPackages.size})=" +
                ClassWatcherService.allowedPackages.sorted().joinToString(","))
            sb.appendLine("deniedPackages(${ClassWatcherService.deniedPackages.size})=" +
                ClassWatcherService.deniedPackages.sorted().joinToString(","))
            sb.appendLine("consensus(threshold=${UsageAggregator.currentThreshold()}):")
            UsageAggregator.snapshot().entries
                .sortedByDescending { it.value }
                .take(10)
                .forEach { sb.appendLine("  ${it.key}=${it.value}") }
            sb.appendLine("domains:")
            DomainUsageAggregator.snapshot().entries
                .sortedByDescending { it.value }
                .take(10)
                .forEach { sb.appendLine("  ${it.key}=${it.value}") }
            sb.appendLine("allowedDomains(${LocalVpnService.allowedDomains.size})=" +
                LocalVpnService.allowedDomains.sorted().joinToString(","))
            sb.appendLine("deniedDomains(${LocalVpnService.deniedDomains.size})=" +
                LocalVpnService.deniedDomains.sorted().joinToString(","))
            sb.append("== END ==")
            conn.send(sb.toString())
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

        if (command.startsWith("P2P_USAGE|")) {
            val parts = command.split("|")
            if (parts.size >= 5) {
                val pkg = parts[1]
                val tsStr = parts[2]
                val sender = parts[3]
                val sig = parts[4]
                if (verifyPeerSignature(sender, "$pkg|$tsStr|$sender", sig)) {
                    val ts = tsStr.toLongOrNull() ?: System.currentTimeMillis()
                    UsageAggregator.record(pkg, sender, ts)
                    android.util.Log.d("Gossip", "peer $sender used $pkg @ $ts (verified)")
                }
            }
            return
        }

        if (command.startsWith("P2P_DOMAIN|")) {
            val parts = command.split("|")
            if (parts.size >= 5) {
                val domain = parts[1]
                val tsStr = parts[2]
                val sender = parts[3]
                val sig = parts[4]
                if (verifyPeerSignature(sender, "$domain|$tsStr|$sender", sig)) {
                    val ts = tsStr.toLongOrNull() ?: System.currentTimeMillis()
                    DomainUsageAggregator.record(domain, sender, ts)
                }
            }
            return
        }

        if (command.startsWith("SET_ALLOWED_APPS")) {
            val parts = command.split("|")
            val apps = parts.drop(1).filter { it.isNotBlank() }.toMutableSet()
            android.util.Log.d("Allowlist", "SET_ALLOWED_APPS 수신: ${apps.size}개")
            // Merge with defaults so system UI / IME / launcher are never blocked
            ClassWatcherService.allowedPackages = (ClassWatcherService.DEFAULT_ALLOWED_PACKAGES + apps).toMutableSet()
            // Teacher's list is authoritative — consensus additions dropped, will
            // rebuild organically on next observation.
            UsageAggregator.forgetConsensusPromotions()
            context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
                .edit()
                .putStringSet("allowed_apps", apps)
                .apply()
            conn.send("OK|SET_ALLOWED_APPS|${apps.size}")
            return
        }

        if (command.startsWith("SET_DENIED_APPS")) {
            val parts = command.split("|")
            val apps = parts.drop(1).filter { it.isNotBlank() }.toMutableSet()
            android.util.Log.d("Denylist", "SET_DENIED_APPS 수신: ${apps.size}개")
            ClassWatcherService.deniedPackages = apps
            context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
                .edit()
                .putStringSet("denied_apps", apps)
                .apply()
            conn.send("OK|SET_DENIED_APPS|${apps.size}")
            return
        }

        if (command.startsWith("SET_DENIED_SITES")) {
            val parts = command.split("|")
            val domains = parts.drop(1).filter { it.isNotBlank() }.toMutableSet()
            LocalVpnService.updateDeniedDomains(domains)
            context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
                .edit()
                .putStringSet("denied_domains", domains)
                .apply()
            conn.send("OK|SET_DENIED_SITES|${domains.size}")
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
            LocalVpnService.deniedDomains = prefs
                .getStringSet("denied_domains", mutableSetOf())!!
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

    /**
     * Verify a signed gossip payload against the sender's mDNS-advertised pubkey.
     * Drops the message on any failure: unknown peer, missing pubkey, bad sig.
     * Called for P2P_USAGE and P2P_DOMAIN only.
     */
    private fun verifyPeerSignature(sender: String, payload: String, sigB64: String): Boolean {
        val pubkey = PeerRegistry.get(sender)?.publicKeyB64
        if (pubkey == null) {
            android.util.Log.w("Gossip", "unknown peer $sender — drop")
            return false
        }
        val ok = PeerIdentity.verify(pubkey, payload.toByteArray(), sigB64)
        if (!ok) android.util.Log.w("Gossip", "bad sig from $sender — drop")
        return ok
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }
}
