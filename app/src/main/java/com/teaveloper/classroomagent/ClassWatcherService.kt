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

        // Report every switch — to connected teachers (Slice 1), to peer agents
        // (Slice 3), and to our own local aggregator (Slice 4). Self counts as a
        // peer so a class where everyone naturally uses X converges without any
        // outside coordination.
        if (pkg !in systemUiPackages) {
            AgentWebSocketServer.instance?.broadcastUsage(pkg)
            PeerGossip.sendUsageEvent(pkg)
            UsageAggregator.record(pkg, PeerIdentity.myName, System.currentTimeMillis())
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

        // Slice 5: soft enforcement.
        // - Allowed apps: hide overlay silently.
        // - Everything else: show an advisory overlay but do NOT forcibly redirect.
        //   The aggregator promotes widely-used apps into allowedPackages, so the
        //   warning disappears once the class converges on X.
        // Hard block (force-back + relaunch) is intentionally removed — teacher
        // override remains available for the exceptional case (later slice).
        if (pkg in allowedPackages) {
            startService(Intent(this, OverlayService::class.java).apply {
                action = "HIDE"
            })
        } else if (pkg !in systemUiPackages) {
            startService(Intent(this, OverlayService::class.java).apply {
                action = "SHOW"
            })
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
