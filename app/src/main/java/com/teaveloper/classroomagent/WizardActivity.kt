package com.teaveloper.classroomagent

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri

/**
 * 학교 관리 모드 진입 마법사. 4단계(접근성 → 오버레이 → 배터리 최적화 → VPN)
 * 를 안내한다. 접근성만 사용자가 켜면 ClassWatcherService 가 나머지 시스템
 * 다이얼로그를 자동으로 탭한다.
 *
 * 화면은 하나의 Activity 안에서 단계별로 갱신되며, 500ms 마다 권한 상태를
 * 폴링해 자동으로 다음 단계로 진행한다. 사용자가 시스템 설정 화면을 다녀오면
 * onResume 에서도 즉시 재평가한다.
 */
class WizardActivity : AppCompatActivity() {

    private enum class Step { ACCESSIBILITY, OVERLAY, BATTERY, VPN, COMPLETE }

    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            refresh()
            if (currentStep != Step.COMPLETE) handler.postDelayed(this, 500L)
        }
    }

    private var currentStep = Step.ACCESSIBILITY

    private lateinit var illustration: ImageView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var path: TextView
    private lateinit var primaryButton: Button
    private lateinit var hint: TextView
    private lateinit var stepIndicator: TextView
    private lateinit var progressDots: LinearLayout
    private lateinit var waitingSpinner: ProgressBar
    private lateinit var waitingText: TextView

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* 결과 무시 — onResume 폴링이 상태 재평가 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wizard)

        illustration = findViewById(R.id.wizardIllustration)
        title = findViewById(R.id.wizardTitle)
        subtitle = findViewById(R.id.wizardSubtitle)
        path = findViewById(R.id.wizardPath)
        primaryButton = findViewById(R.id.wizardPrimaryButton)
        hint = findViewById(R.id.wizardHint)
        stepIndicator = findViewById(R.id.wizardStepIndicator)
        progressDots = findViewById(R.id.progressDots)
        waitingSpinner = findViewById(R.id.wizardWaitingSpinner)
        waitingText = findViewById(R.id.wizardWaitingText)

        buildDots()
        // 접근성 이후 단계는 auto-tap 에 위임 — 마법사가 살아있는 동안 상시 ON.
        ClassWatcherService.autoGrantPending = true
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 모든 권한이 부여됐을 때만 auto-grant 를 끈다. 사용자가 중간에 마법사를
        // 강제로 닫으면 여전히 다이얼로그를 자동 처리해야 할 수 있음.
        if (currentStep == Step.COMPLETE) {
            ClassWatcherService.autoGrantPending = false
        }
    }

    /** 현재 권한 상태를 조사해 다음 미완료 단계를 currentStep 으로 잡고 화면 갱신. */
    private fun refresh() {
        val next = nextIncompleteStep()
        if (next != currentStep) {
            currentStep = next
        }
        render()
        if (currentStep == Step.COMPLETE) {
            ClassWatcherService.autoGrantPending = false
            finish()
        }
    }

    private fun nextIncompleteStep(): Step {
        if (!isAccessibilityEnabled()) return Step.ACCESSIBILITY
        if (!Settings.canDrawOverlays(this)) return Step.OVERLAY
        if (!isBatteryExempt()) return Step.BATTERY
        if (!isVpnGranted()) return Step.VPN
        return Step.COMPLETE
    }

    private fun render() {
        updateDots()
        when (currentStep) {
            Step.ACCESSIBILITY -> renderAccessibility()
            Step.OVERLAY -> renderAutoStep(
                indicator = "2 / 4 단계",
                titleText = "다른 앱 위에 표시 승인",
                subtitleText = "학교 안내 배너를 보여주기 위한 권한입니다.\n방금 켠 접근성이 자동으로 처리하고 있어요."
            )
            Step.BATTERY -> renderAutoStep(
                indicator = "3 / 4 단계",
                titleText = "배터리 사용 예외 승인",
                subtitleText = "수업 중 앱이 꺼지지 않도록 배터리 절약 대상에서 제외합니다.\n자동으로 진행됩니다."
            )
            Step.VPN -> renderAutoStep(
                indicator = "4 / 4 단계",
                titleText = "네트워크 필터 승인",
                subtitleText = "수업 중 금지 사이트를 차단하기 위한 로컬 필터입니다.\n외부로 데이터를 보내지 않아요."
            )
            Step.COMPLETE -> Unit
        }
    }

    private fun renderAccessibility() {
        stepIndicator.text = "1 / 4 단계"
        title.text = "접근성 서비스 켜기"
        subtitle.text = "이 단계만 직접 켜주시면 나머지는 자동으로 진행됩니다."
        path.text = accessibilityPathForOem()
        path.visibility = View.VISIBLE
        primaryButton.text = "접근성 설정 열기"
        primaryButton.visibility = View.VISIBLE
        hint.text = "화면 이동 후 돌아오면 자동으로 다음 단계로 진행됩니다"
        hint.visibility = View.VISIBLE
        waitingSpinner.visibility = View.GONE
        waitingText.visibility = View.GONE
        primaryButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    /**
     * 접근성이 켜진 뒤 단계들은 사용자 조작이 원칙적으로 필요 없다.
     * 서비스가 다이얼로그를 자동 탭할 것이므로 마법사는 "잠시 기다려주세요"
     * 상태로 표시하고 사용자가 원한다면 "직접 열기" 로 fallback.
     */
    private fun renderAutoStep(indicator: String, titleText: String, subtitleText: String) {
        stepIndicator.text = indicator
        title.text = titleText
        subtitle.text = subtitleText
        path.visibility = View.GONE
        waitingSpinner.visibility = View.VISIBLE
        waitingText.visibility = View.VISIBLE
        primaryButton.text = "직접 열기"
        primaryButton.visibility = View.VISIBLE
        hint.text = "몇 초 안에 다음 단계로 넘어가지 않으면 위 버튼을 눌러주세요"
        hint.visibility = View.VISIBLE
        // 폴백: 자동 탭 실패 시 사용자가 직접 진입할 수 있도록
        primaryButton.setOnClickListener { openCurrentStepSettings() }
    }

    private fun openCurrentStepSettings() {
        when (currentStep) {
            Step.OVERLAY -> startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
            )
            Step.BATTERY -> startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData("package:$packageName".toUri())
            )
            Step.VPN -> VpnService.prepare(this)?.let { vpnLauncher.launch(it) }
            else -> Unit
        }
    }

    private fun buildDots() {
        progressDots.removeAllViews()
        repeat(4) { progressDots.addView(makeDot()) }
        updateDots()
    }

    private fun makeDot(): View {
        val v = View(this)
        val size = (12 * resources.displayMetrics.density).toInt()
        val margin = (6 * resources.displayMetrics.density).toInt()
        v.layoutParams = LinearLayout.LayoutParams(size, size).apply {
            leftMargin = margin
            rightMargin = margin
        }
        v.setBackgroundResource(R.drawable.dot_pending)
        return v
    }

    private fun updateDots() {
        val doneIndex = when (currentStep) {
            Step.ACCESSIBILITY -> 0
            Step.OVERLAY -> 1
            Step.BATTERY -> 2
            Step.VPN -> 3
            Step.COMPLETE -> 4
        }
        for (i in 0 until progressDots.childCount) {
            val res = when {
                i < doneIndex -> R.drawable.dot_done
                i == doneIndex && currentStep != Step.COMPLETE -> R.drawable.dot_active
                else -> R.drawable.dot_pending
            }
            progressDots.getChildAt(i).setBackgroundResource(res)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun isBatteryExempt(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isVpnGranted(): Boolean = VpnService.prepare(this) == null

    /**
     * OEM 마다 접근성 설정 진입 경로 문구가 다르다. 정확한 경로를 표시해
     * 초보자가 길을 잃지 않게 한다.
     */
    private fun accessibilityPathForOem(): String {
        val m = (Build.MANUFACTURER ?: "").lowercase()
        return when {
            m.contains("samsung") -> "설정 → 접근성 → 설치된 앱 → ClassroomAgent → 사용"
            m.contains("google") || m.contains("pixel") -> "설정 → 접근성 → 다운로드한 앱 → ClassroomAgent"
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> "설정 → 추가 설정 → 접근성 → ClassroomAgent"
            m.contains("lg") -> "설정 → 일반 → 접근성 → ClassroomAgent"
            m.contains("oppo") || m.contains("realme") -> "설정 → 편의 도구 → 접근성 → ClassroomAgent"
            else -> "설정 → 접근성 → ClassroomAgent"
        }
    }
}
