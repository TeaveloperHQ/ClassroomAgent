package com.yourname.classroomagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class ClassWatcherService : AccessibilityService() {

    companion object {
        var instance: ClassWatcherService? = null
        var isClassInSession = false

        val DEFAULT_ALLOWED_PACKAGES = setOf(
            "com.microsoft.office.onenote",
            "com.yourname.classroomagent",
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.launcher3",
            "com.sec.android.app.launcher"
        )

        var allowedPackages: MutableSet<String> = DEFAULT_ALLOWED_PACKAGES.toMutableSet()
    }

    override fun onServiceConnected() {
        instance = this
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
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}