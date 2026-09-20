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
    /**
     * 진입한 스텝의 시스템 화면을 1회만 자동 열도록 잠금. 폴링이 도는 동안
     * 같은 스텝에서 여러 번 인텐트를 쏘아 유저가 열어놓은 화면을 덮어쓰지
     * 않게 한다.
     */
    private var launchedStep: Step? = null

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
            // 새 스텝에 진입하면 아직 인텐트 안 쏜 상태로 리셋
            launchedStep = null
        }
        render()
        if (currentStep == Step.COMPLETE) {
            ClassWatcherService.autoGrantPending = false
            finish()
            return
        }
        // 접근성 이후 단계는 사용자 대신 자동으로 시스템 화면을 연다. 화면이
        // 열리면 ClassWatcherService 가 auto-tap 을 시도하고, 실패하면 사용자가
        // 보이는 그 화면에서 손으로 승인. 어느 쪽이든 폴링이 감지해 다음 스텝.
        if (currentStep != Step.ACCESSIBILITY && launchedStep != currentStep) {
            launchedStep = currentStep
            handler.postDelayed({ openCurrentStepSettings() }, 400L)
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
            Step.OVERLAY -> renderGuidedStep(
                indicator = "2 / 4 단계",
                titleText = "다른 앱 위에 표시 승인",
                subtitleText = "곧 뜨는 화면에서 스위치를 오른쪽으로 밀어주세요.\n뒤로가기를 누르면 다음 단계로 넘어갑니다."
            )
            Step.BATTERY -> renderGuidedStep(
                indicator = "3 / 4 단계",
                titleText = "배터리 사용 예외 승인",
                subtitleText = "곧 뜨는 알림에서 '허용' 을 눌러주세요.\n수업 중 앱이 꺼지지 않게 하기 위한 설정입니다."
            )
            Step.VPN -> renderGuidedStep(
                indicator = "4 / 4 단계",
                titleText = "네트워크 필터 승인",
                subtitleText = "곧 뜨는 알림에서 '확인' 을 눌러주세요.\n외부로 데이터를 보내지 않는 로컬 필터입니다."
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
     * 접근성 이후 단계는 refresh() 가 진입 즉시 시스템 화면을 자동으로 열어준다.
     * 사용자는 열린 화면에서 스위치/버튼만 누르면 됨. 마법사는 그동안 폴링하며
     * 감지되면 다음 스텝으로 넘어간다. 화면이 닫혔는데 승인 안 됐으면 폴링이
     * 같은 스텝에 남아 있으므로 "다시 열기" 버튼으로 재시도.
     */
    private fun renderGuidedStep(indicator: String, titleText: String, subtitleText: String) {
        stepIndicator.text = indicator
        title.text = titleText
        subtitle.text = subtitleText
        path.visibility = View.GONE
        waitingSpinner.visibility = View.VISIBLE
        waitingText.text = "설정 화면을 여는 중..."
        waitingText.visibility = View.VISIBLE
        primaryButton.text = "설정 화면 다시 열기"
        primaryButton.visibility = View.VISIBLE
        hint.text = "화면이 뜨지 않으면 위 버튼을 누르세요"
        hint.visibility = View.VISIBLE
        primaryButton.setOnClickListener {
            launchedStep = null
            openCurrentStepSettings()
        }
    }

    private fun openCurrentStepSettings() {
        when (currentStep) {
            Step.OVERLAY -> safeStart(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()),
                fallback = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            )
            Step.BATTERY -> safeStart(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData("package:$packageName".toUri()),
                // 일부 OEM 은 위 다이얼로그 인텐트를 무시함 — 배터리 최적화 목록
                // 페이지로 폴백해서 사용자가 앱을 찾아 스위치 오프하도록 유도
                fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
            Step.VPN -> VpnService.prepare(this)?.let {
                try { vpnLauncher.launch(it) } catch (e: Exception) {
                    android.util.Log.w("Wizard", "VPN 컨센트 실패: ${e.message}")
                }
            }
            else -> Unit
        }
    }

    private fun safeStart(primary: Intent, fallback: Intent) {
        try {
            startActivity(primary)
        } catch (e: Exception) {
            android.util.Log.w("Wizard", "primary intent 실패 → fallback: ${e.message}")
            try { startActivity(fallback) } catch (e2: Exception) {
                android.util.Log.e("Wizard", "fallback 도 실패: ${e2.message}")
            }
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
