package com.yourname.classroomagent

import androidx.core.net.toUri
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var activeDialog: AlertDialog? = null

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
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    override fun onPause() {
        super.onPause()
        activeDialog?.dismiss()
        activeDialog = null
    }

    private fun checkPermissions() {
        if (activeDialog?.isShowing == true) return

        // 초기 설정이 완료되지 않은 경우 설정 다이얼로그 먼저 표시
        val prefs = getSharedPreferences("setup", MODE_PRIVATE)
        if (prefs.getString("deviceName", null) == null) {
            showSetupDialog()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            showPermissionDialog(
                title = "'다른 앱 위에 표시' 권한 필요",
                message = "수업 관리를 위해 '다른 앱 위에 표시' 권한이 필요합니다.\n\n" +
                        "📋 설정 경로\n" +
                        "설정 → 앱 → ClassroomAgent\n→ 다른 앱 위에 표시\n→ 허용\n\n" +
                        "'설정하러 가기'를 누르면 해당 화면으로 이동합니다.",
                onConfirm = {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            "package:$packageName".toUri()
                        )
                    )
                }
            )
            return
        }

        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val accessibilityEnabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }

        if (!accessibilityEnabled) {
            showPermissionDialog(
                title = "접근성 권한 필요",
                message = "수업 중 앱 전환 감지를 위해 접근성 권한이 필요합니다.\n\n" +
                        "📋 설정 경로\n" +
                        "설정 → 접근성\n→ 설치된 앱\n→ ClassroomAgent\n→ 사용 → 허용\n\n" +
                        "'설정하러 가기'를 누르면 해당 화면으로 이동합니다.",
                onConfirm = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )
        }
    }

    private fun showSetupDialog() {
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
            .setTitle("기기 설정")
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

            startForegroundService(Intent(this, OverlayService::class.java))
            checkPermissions()
        }
    }

    private fun showPermissionDialog(title: String, message: String, onConfirm: () -> Unit) {
        activeDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("설정하러 가기") { dialog, _ ->
                dialog.dismiss()
                activeDialog = null
                onConfirm()
            }
            .setCancelable(false)
            .show()
    }
}
