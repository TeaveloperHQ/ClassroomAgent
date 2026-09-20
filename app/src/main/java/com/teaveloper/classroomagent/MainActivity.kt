package com.teaveloper.classroomagent

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var activeDialog: AlertDialog? = null

    private val editApprovedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            showSetupDialog(isResetup = true)
        }
    }

    private val editRejectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Toast.makeText(this@MainActivity, "선생님이 학적 수정 요청을 거절했습니다.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 설정이 완료된 경우에만 서비스 시작
        val prefs = getSharedPreferences("setup", MODE_PRIVATE)
        if (prefs.getString("deviceName", null) != null) {
            startForegroundService(Intent(this, OverlayService::class.java))
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            ClassWatcherService.isClassInSession = true
            Toast.makeText(this, "수업 모드 시작", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            ClassWatcherService.isClassInSession = false
            Toast.makeText(this, "수업 모드 종료", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnEditRequest).setOnClickListener {
            AgentWebSocketServer.editRequested = true
            Toast.makeText(this, "학적 수정 요청을 전송했습니다", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            editApprovedReceiver,
            IntentFilter("com.teaveloper.classroomagent.EDIT_APPROVED"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            editRejectedReceiver,
            IntentFilter("com.teaveloper.classroomagent.EDIT_REJECTED"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        checkPermissions()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(editApprovedReceiver)
        unregisterReceiver(editRejectedReceiver)
        activeDialog?.dismiss()
        activeDialog = null
    }

    private fun checkPermissions() {
        if (activeDialog?.isShowing == true) return

        // 초기 설정(학년/반/번호/이름)이 아직이면 그거부터
        val prefs = getSharedPreferences("setup", MODE_PRIVATE)
        if (prefs.getString("deviceName", null) == null) {
            showSetupDialog()
            return
        }

        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val accessibilityEnabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
        val overlayGranted = Settings.canDrawOverlays(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryExempt = pm.isIgnoringBatteryOptimizations(packageName)
        val vpnGranted = VpnService.prepare(this) == null

        // 하나라도 미완이면 전용 마법사로 위임 — 초보자가 안심하고 끝까지 따라오도록
        // 풀스크린 4스텝 UI + 자동 폴링 + 접근성 auto-tap 조합.
        if (!accessibilityEnabled || !overlayGranted || !batteryExempt || !vpnGranted) {
            startActivity(Intent(this, WizardActivity::class.java))
        } else {
            ClassWatcherService.autoGrantPending = false
        }
    }

    private fun showSetupDialog(isResetup: Boolean = false) {
        val gradeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("1학년", "2학년", "3학년")
            )
        }

        val classSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                (1..20).map { "%02d반".format(it) }
            )
        }

        val numberSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                (1..40).map { "%02d번".format(it) }
            )
        }

        val nameEditText = EditText(this).apply {
            hint = "이름 입력"
            inputType = InputType.TYPE_CLASS_TEXT
        }

        // 재설정 시 기존 값 미리 채우기
        if (isResetup) {
            val existing = getSharedPreferences("setup", MODE_PRIVATE)
                .getString("deviceName", "") ?: ""
            if (existing.length >= 5) {
                val grade = (existing[0] - '0').coerceIn(1, 3)
                val classNum = existing.substring(1, 3).toIntOrNull()?.coerceIn(1, 20) ?: 1
                val number = existing.substring(3, 5).toIntOrNull()?.coerceIn(1, 40) ?: 1
                gradeSpinner.setSelection(grade - 1)
                classSpinner.setSelection(classNum - 1)
                numberSpinner.setSelection(number - 1)
                nameEditText.setText(existing.substring(5))
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 24, 64, 8)
            addView(TextView(this@MainActivity).apply { text = "학년" })
            addView(gradeSpinner)
            addView(TextView(this@MainActivity).apply { text = "반" })
            addView(classSpinner)
            addView(TextView(this@MainActivity).apply { text = "번호" })
            addView(numberSpinner)
            addView(TextView(this@MainActivity).apply { text = "이름" })
            addView(nameEditText)
        }

        activeDialog = AlertDialog.Builder(this)
            .setTitle(if (isResetup) "학적 정보 수정" else "기기 설정")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("확인", null)
            .show()

        activeDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val name = nameEditText.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "이름을 입력해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val grade = gradeSpinner.selectedItemPosition + 1
            val classNum = "%02d".format(classSpinner.selectedItemPosition + 1)
            val number = "%02d".format(numberSpinner.selectedItemPosition + 1)
            val deviceName = "$grade$classNum$number$name"

            getSharedPreferences("setup", MODE_PRIVATE).edit()
                .putString("deviceName", deviceName)
                .apply()

            activeDialog?.dismiss()
            activeDialog = null

            if (isResetup) {
                startService(Intent(this, OverlayService::class.java).apply {
                    action = "RE_REGISTER_MDNS"
                })
            } else {
                startForegroundService(Intent(this, OverlayService::class.java))
                checkPermissions()
            }
        }
    }

}
