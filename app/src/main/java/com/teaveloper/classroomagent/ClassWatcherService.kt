package com.teaveloper.classroomagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClassWatcherService : AccessibilityService() {

    private var consecutiveNonSuspicious = 0
    private val guidance by lazy { GuidanceOverlay(this) }

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
         * 최초 설정 마법사(WizardActivity)가 켜두는 스위치. true 인 동안 이
         * 서비스는 VPN 컨센트 다이얼로그(오직 그것 하나) 의 '확인' 버튼을 탭해
         * 준다. 다른 시스템 화면에는 절대 손대지 않음 — 사용자가 마법사 안내를
         * 따라 직접 스위치를 눌러 승인한다. 마법사 완료 시 false 로 리셋.
         */
        @Volatile var autoGrantPending = false

        // VPN 컨센트 다이얼로그만 대상. 오버레이/배터리 등 일반 설정 화면은
        // 사용자가 마법사의 안내대로 직접 탭한다.
        private val VPN_DIALOG_PACKAGES = setOf(
            "com.android.vpndialogs",
            "com.samsung.android.vpndialogs",
        )
        private val VPN_CONFIRM_LABELS = listOf("확인", "OK", "Allow", "허용")

        // 오버레이 안내(터치 안 함, 시각적 표시만) 를 그릴 대상 화면.
        private val GUIDANCE_PACKAGES = setOf(
            "com.android.settings",
            "com.samsung.android.settings",
            "com.android.vpndialogs",
            "com.samsung.android.vpndialogs",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
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

        // 최초 세팅 중 VPN 컨센트 다이얼로그만 자동 확인 (초보자에게 가장
        // 혼란스러운 다이얼로그 하나). 다른 시스템 화면은 손대지 않음.
        if (autoGrantPending && pkg in VPN_DIALOG_PACKAGES) {
            tryConfirmVpnDialog()
        }
        // 마법사 진행 중이면 시스템 설정/다이얼로그 위에 "여기 눌러주세요"
        // 화살표 안내를 그린다. 자동으로 누르진 않고 시각적으로만 지시.
        if (autoGrantPending) {
            if (pkg in GUIDANCE_PACKAGES) updateGuidance()
            else guidance.hide()
        } else {
            guidance.hide()
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
        guidance.hide()
        instance = null
        super.onDestroy()
    }

    /**
     * 시스템 설정/다이얼로그 화면에서 사용자가 정확히 눌러야 할 위젯을 찾아
     * 그 좌표 위에 화살표+힌트 오버레이를 그린다. 실제 탭은 사용자가 함.
     */
    private fun updateGuidance() {
        val root = rootInActiveWindow ?: run { guidance.hide(); return }

        // 우선순위: (1) 미체크 스위치 — 오버레이/접근성 설정 페이지
        //          (2) '허용' 버튼 — 배터리 최적화 다이얼로그
        //          (3) '확인' 버튼 — VPN 컨센트 다이얼로그
        val switch = findFirstNode(root) { n ->
            val cn = n.className?.toString() ?: return@findFirstNode false
            (cn == "android.widget.Switch" ||
                cn.endsWith(".SwitchCompat") ||
                cn.contains("SeslSwitchBar")
            ) && n.isCheckable && !n.isChecked && n.isEnabled
        }
        if (switch != null) {
            val r = Rect().also { switch.getBoundsInScreen(it) }
            if (!r.isEmpty) {
                guidance.show(r, "이 스위치를 켜주세요")
                return
            }
        }

        for (label in listOf("허용", "확인", "Allow", "OK")) {
            val node = root.findAccessibilityNodeInfosByText(label)?.firstOrNull() ?: continue
            val clickable = findClickableAncestor(node) ?: node
            val r = Rect().also { clickable.getBoundsInScreen(it) }
            if (!r.isEmpty) {
                guidance.show(r, "'$label' 을 눌러주세요")
                return
            }
        }
        guidance.hide()
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
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

    /**
     * VPN 컨센트 다이얼로그의 "확인" 버튼만 탭한다. 이 다이얼로그는 우리 앱이
     * VpnService.prepare() 를 호출했을 때만 Android 가 띄워준다 — 즉 언제
     * 어떤 화면이 뜰지를 우리가 명시적으로 트리거한 결과. 사용자의 예상 밖
     * 화면을 조작하는 게 아니라 자기 앱 요청에 동의하는 셈.
     *
     * adb logcat -s AutoGrant 로 동작 확인 가능.
     */
    private fun tryConfirmVpnDialog() {
        val root = rootInActiveWindow ?: return
        for (label in VPN_CONFIRM_LABELS) {
            val nodes = root.findAccessibilityNodeInfosByText(label) ?: continue
            for (n in nodes) {
                var current: AccessibilityNodeInfo? = n
                while (current != null) {
                    if (current.isClickable && current.isEnabled) {
                        current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        android.util.Log.d("AutoGrant", "VPN 확인 탭: '$label'")
                        return
                    }
                    current = current.parent
                }
            }
        }
    }
}
