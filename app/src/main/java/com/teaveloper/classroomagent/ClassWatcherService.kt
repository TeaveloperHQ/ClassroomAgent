package com.teaveloper.classroomagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClassWatcherService : AccessibilityService() {

    private var consecutiveNonSuspicious = 0

    private val systemUiPackages = setOf(
        "com.android.systemui",
        "com.samsung.android.cocktailbarservice",
        "com.samsung.android.app.cocktailbarservice",
        "com.sec.android.cocktailbar",
        "com.samsung.android.edge.injection",
        "com.samsung.android.app.edgetouch"
    )

    companion object {
        var instance: ClassWatcherService? = null
        var isClassInSession = false

        val DEFAULT_ALLOWED_PACKAGES = setOf(
            "com.microsoft.office.onenote",
            "com.teaveloper.classroomagent",
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.launcher3",
            "com.sec.android.app.launcher"
        )

        var allowedPackages: MutableSet<String> = DEFAULT_ALLOWED_PACKAGES.toMutableSet()
        var violationCount: MutableMap<String, Int> = mutableMapOf()
        var suspiciousPackage: String? = null
        var suspiciousTime: String? = null
    }

    override fun onServiceConnected() {
        instance = this
        // Load persisted allowlist (teacher-pushed via SET_ALLOWED_APPS). Union with defaults
        // so system UI / IME / launcher stay allowed regardless of what the teacher sent.
        val persisted = getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
            .getStringSet("allowed_apps", emptySet()) ?: emptySet()
        allowedPackages = (DEFAULT_ALLOWED_PACKAGES + persisted).toMutableSet()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        android.util.Log.d("ClassWatcher", "현재 앱: $pkg")

        if (!isClassInSession) return

        // Report every switch to connected teachers so the aggregation layer can
        // build a class-wide picture of what's actually being used. Dedup happens
        // inside the WebSocket server.
        if (pkg !in systemUiPackages) {
            AgentWebSocketServer.instance?.broadcastUsage(pkg)
        }

        val currentSuspicious = suspiciousPackage
        if (currentSuspicious != null) {
            if (pkg == currentSuspicious) {
                consecutiveNonSuspicious = 0
            } else {
                consecutiveNonSuspicious++
                if (consecutiveNonSuspicious >= 10) {
                    suspiciousPackage = null
                    suspiciousTime = null
                    violationCount[currentSuspicious] = 0
                    consecutiveNonSuspicious = 0
                }
            }
        }

        if (pkg in allowedPackages) {
            startService(Intent(this, OverlayService::class.java).apply {
                action = "HIDE"
            })
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_BACK)
            val intent = packageManager.getLaunchIntentForPackage("com.microsoft.office.onenote")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(intent)
            }

            if (pkg in allowedPackages || pkg in systemUiPackages) {
                android.util.Log.d("ClassWatcher", "우회 시도 제외 (시스템UI): $pkg")
                return
            }
            val count = violationCount.getOrDefault(pkg, 0) + 1
            violationCount[pkg] = count
            if (count >= 10) {
                suspiciousPackage = pkg
                suspiciousTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                consecutiveNonSuspicious = 0
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
