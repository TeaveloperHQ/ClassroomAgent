package com.yourname.classroomagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            android.util.Log.d("BootReceiver", "부팅 완료 - 서비스 시작")
            context.startForegroundService(
                Intent(context, OverlayService::class.java)
            )
        }
    }
}