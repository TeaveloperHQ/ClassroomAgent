package com.teaveloper.classroomagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
        /**
         * 최초 설정 중에만 켜지는 auto-grant 스위치. true 인 동안 이 서비스가
         * VPN 컨센트/배터리 최적화/오버레이 등 시스템 권한 다이얼로그를 감지해
         * 자동으로 허용 버튼을 탭한다. MainActivity 의 마법사가 모든 권한을
         * 확인하면 false 로 되돌린다.
         */
        @Volatile var autoGrantPending = false

        private val PERMISSION_DIALOG_PACKAGES = setOf(
            "com.android.vpndialogs",
            "com.samsung.android.vpndialogs",
            "com.android.settings",
            "com.samsung.android.settings",
            "com.android.systemui",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.securitycenter",
        )
        private val AUTO_CONFIRM_LABELS = listOf(
            "허용", "허용함", "확인", "예", "동의", "계속", "승인",
            "Allow", "OK", "Yes", "Continue", "Approve", "Accept",
        )
        /**
         * Epoch ms of the last START. During the grace period after START,
         * pending-app banners are suppressed — students shouldn't get scolded
         * for opening a normal-but-new app during the first few minutes of
         * class while consensus hasn't formed yet.
         */
        @Volatile var classStartedAtMs: Long = 0L
        const val BOOTSTRAP_GRACE_MS = 5 * 60 * 1000L

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

        /**
         * Guards read-modify-write on allowedPackages/deniedPackages. @Volatile
         * gives visibility of the reference swap but not atomicity of
         * `set = (set + x).toMutableSet()`; without this lock, a consensus
         * promotion and a teacher SET_ALLOWED_APPS arriving on different
         * threads can silently drop one of the writes. Every reassignment of
         * these two fields must happen inside synchronized(allowlistLock).
         */
        val allowlistLock: Any = Any()

        @Volatile
        var allowedPackages: MutableSet<String> = DEFAULT_ALLOWED_PACKAGES.toMutableSet()
        /**
         * Teacher-mandated hard block. Overrides consensus: even if 100% of the
         * class uses X, if it's in deniedPackages the agent still force-redirects.
         * Populated only via SET_DENIED_APPS from an authenticated teacher.
         */
        @Volatile
        var deniedPackages: MutableSet<String> = mutableSetOf()
        var violationCount: MutableMap<String, Int> = mutableMapOf()
        var suspiciousPackage: String? = null
        var suspiciousTime: String? = null

        /**
         * Distinct pkgs used positively during the current session (i.e. hit
         * the allowedPackages branch or the history fast-path). Persisted to
         * BehaviorHistory on STOP, then cleared. Populated in enforcement.
         */
        val sessionUsedPackages: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }

    override fun onServiceConnected() {
        instance = this
        // Load persisted allowlist (teacher-pushed via SET_ALLOWED_APPS). Union with defaults
        // so system UI / IME / launcher stay allowed regardless of what the teacher sent.
        val prefs = getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
        val persisted = prefs.getStringSet("allowed_apps", emptySet()) ?: emptySet()
        synchronized(allowlistLock) {
            allowedPackages = (DEFAULT_ALLOWED_PACKAGES + persisted).toMutableSet()
            deniedPackages = (prefs.getStringSet("denied_apps", emptySet()) ?: emptySet()).toMutableSet()
        }
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        android.util.Log.d("ClassWatcher", "현재 앱: $pkg")

        // 최초 세팅 중 시스템 권한 다이얼로그가 뜨면 자동으로 허용 탭.
        // isClassInSession 게이트보다 앞에 두어야 세션 시작 전에도 동작.
        if (autoGrantPending && pkg in PERMISSION_DIALOG_PACKAGES) {
            tryAutoConfirm()
        }

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

        // Enforcement tiers (highest priority first):
        // 1. deniedPackages (teacher-mandated hard block) → force redirect + overlay
        // 2. allowedPackages (default + teacher + consensus) → hide overlay silently
        // 3. everything else (unknown app) → soft advisory overlay, no forced action
        //
        // Rationale for keeping hard block for tier 1: "특별한 경우에만 controller
        // 개입" — when the teacher explicitly denies X, that IS the special case.
        when {
            pkg in deniedPackages -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                performGlobalAction(GLOBAL_ACTION_BACK)
                performGlobalAction(GLOBAL_ACTION_BACK)
                val intent = packageManager.getLaunchIntentForPackage("com.microsoft.office.onenote")
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    startActivity(intent)
                }
                startService(Intent(this, OverlayService::class.java).apply {
                    action = "SHOW"
                    putExtra("reason", "DENIED")
                })
                val count = violationCount.getOrDefault(pkg, 0) + 1
                violationCount[pkg] = count
                if (count >= 10) {
                    suspiciousPackage = pkg
                    suspiciousTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    consecutiveNonSuspicious = 0
                    EventLog.record("DENIED_REPEAT", pkg)
                }
            }
            pkg in allowedPackages -> {
                startService(Intent(this, OverlayService::class.java).apply {
                    action = "HIDE"
                })
                sessionUsedPackages += pkg
            }
            BehaviorHistory.isRegular(pkg) -> {
                // Personal history says this pkg is normal-for-this-time-slot.
                // Treat as allowed, promote into allowedPackages so DIAG/STATUS
                // reflect it and the aggregator's decay logic can still remove it
                // if the student's habit shifts.
                synchronized(allowlistLock) {
                    allowedPackages = (allowedPackages + pkg).toMutableSet()
                }
                startService(Intent(this, OverlayService::class.java).apply {
                    action = "HIDE"
                })
                sessionUsedPackages += pkg
                EventLog.record("HISTORY_UNLOCK", pkg)
            }
            pkg !in systemUiPackages -> {
                val inGrace = classStartedAtMs > 0 &&
                    System.currentTimeMillis() - classStartedAtMs < BOOTSTRAP_GRACE_MS
                if (!inGrace) {
                    val activeCount = UsageAggregator.activeCount(pkg)
                    val threshold = UsageAggregator.currentThreshold()
                    startService(Intent(this, OverlayService::class.java).apply {
                        action = "SHOW"
                        putExtra("reason", "PENDING")
                        putExtra("count", activeCount)
                        putExtra("threshold", threshold)
                    })
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
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * 시스템 권한 다이얼로그·설정 화면에서 "허용/확인" 계열 버튼(또는 미체크
     * Switch) 을 찾아 탭한다. 실패하면 조용히 리턴 — 다음 이벤트에서 다시 시도.
     *
     * adb logcat -s AutoGrant 로 실제 동작 확인 가능.
     */
    private fun tryAutoConfirm() {
        val root = rootInActiveWindow ?: run {
            android.util.Log.d("AutoGrant", "rootInActiveWindow=null, skip")
            return
        }
        for (label in AUTO_CONFIRM_LABELS) {
            val nodes = root.findAccessibilityNodeInfosByText(label) ?: continue
            for (n in nodes) {
                if (clickIfPossible(n)) {
                    android.util.Log.d("AutoGrant", "탭 성공: text='$label' cls=${n.className}")
                    return
                }
            }
        }
        // 오버레이 권한 화면은 다이얼로그가 아니라 스위치 하나짜리 페이지.
        // 미체크 상태의 Switch 나 SwitchCompat, Samsung 의 SeekBar 커스텀 스위치를 찾아 토글.
        val switch = findFirstNode(root) { n ->
            val cn = n.className?.toString() ?: return@findFirstNode false
            (cn == "android.widget.Switch" || cn.endsWith(".SwitchCompat") ||
                cn == "androidx.appcompat.widget.SwitchCompat" ||
                cn.contains("SeslSwitchBar") // Samsung One UI
            ) && n.isCheckable && !n.isChecked && n.isEnabled
        }
        if (switch != null) {
            val ok = switch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            android.util.Log.d("AutoGrant", "스위치 탭 결과=$ok cls=${switch.className}")
            return
        }
        android.util.Log.d("AutoGrant", "매칭 노드 없음")
    }

    private fun clickIfPossible(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.isEnabled) {
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun findFirstNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findFirstNode(child, predicate)?.let { return it }
        }
        return null
    }
}
