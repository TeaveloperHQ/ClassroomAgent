package com.yourname.classroomagent

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: TextView? = null
    private var webSocketServer: AgentWebSocketServer? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
        startWebSocketServer()
        registerMdns()
    }

    private fun startWebSocketServer() {
        webSocketServer = AgentWebSocketServer(8080, this) { command, conn ->
            android.util.Log.d("WebSocket", "명령 처리: $command")
            when (command.trim()) {
                "START" -> {
                    ClassWatcherService.isClassInSession = true
                }
                "STOP" -> {
                    ClassWatcherService.isClassInSession = false
                }
            }
        }
        webSocketServer?.start()
    }

    private fun registerMdns() {
        // 사용자가 설정한 기기 이름, 없으면 모델명으로 폴백
        val deviceName = Settings.Global.getString(contentResolver, "device_name")
            ?.takeIf { it.isNotBlank() }
            ?: android.os.Build.MODEL

        android.util.Log.d("mDNS", "기기 이름: $deviceName")

        val serviceInfo = android.net.nsd.NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = "_classroomagent._tcp."
            port = 8080
        }

        val nsdManager = getSystemService(NSD_SERVICE) as android.net.nsd.NsdManager
        nsdManager.registerService(
            serviceInfo,
            android.net.nsd.NsdManager.PROTOCOL_DNS_SD,
            object : android.net.nsd.NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: android.net.nsd.NsdServiceInfo) {
                    android.util.Log.d("mDNS", "등록 완료: ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: android.net.nsd.NsdServiceInfo, code: Int) {
                    android.util.Log.e("mDNS", "등록 실패: $code")
                }
                override fun onServiceUnregistered(info: android.net.nsd.NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: android.net.nsd.NsdServiceInfo, code: Int) {}
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "SHOW" -> showOverlay()
            "HIDE" -> hideOverlay()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        webSocketServer?.stop()
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        val channelId = "overlay_service"
        val channel = NotificationChannel(
            channelId,
            "수업 관리 서비스",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        return Notification.Builder(this, channelId)
            .setContentTitle("수업 모드")
            .setContentText("수업 관리 서비스 실행 중")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun showOverlay() {
        android.util.Log.d("OverlayService", "showOverlay 호출됨")
        if (overlayView != null) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        overlayView = TextView(this).apply {
            text = "수업 중입니다\nOneNote만 사용할 수 있습니다"
            textSize = 24f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(220, 0, 0, 0))
            gravity = Gravity.CENTER
        }
        windowManager.addView(overlayView, params)
    }

    private fun hideOverlay() {
        android.util.Log.d("OverlayService", "hideOverlay 호출됨")
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}