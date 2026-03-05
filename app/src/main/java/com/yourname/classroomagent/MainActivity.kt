package com.yourname.classroomagent

import androidx.core.net.toUri
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var activeDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startForegroundService(Intent(this, OverlayService::class.java))

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
