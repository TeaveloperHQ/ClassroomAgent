package com.teaveloper.classroomagent

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: TextView? = null
    private var webSocketServer: AgentWebSocketServer? = null
    private var nsdManager: android.net.nsd.NsdManager? = null
    private var nsdListener: android.net.nsd.NsdManager.RegistrationListener? = null
    private var discoveryListener: android.net.nsd.NsdManager.DiscoveryListener? = null
    private var pendingReregister = false
    private var ownServiceName: String? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
        PeerIdentity.init(this)
        loadAllowedApps()
        startWebSocketServer()
        registerMdns()
        discoverPeers()
    }

    private fun loadAllowedApps() {
        val prefs = getSharedPreferences("setup", MODE_PRIVATE)
        val savedApps = prefs.getString("allowed_apps", null)
        if (savedApps == null) {
            prefs.edit()
                .putString("allowed_apps", ClassWatcherService.DEFAULT_ALLOWED_PACKAGES.joinToString(","))
                .apply()
            ClassWatcherService.allowedPackages = ClassWatcherService.DEFAULT_ALLOWED_PACKAGES.toMutableSet()
        } else {
            val packages = savedApps.split(",").filter { it.isNotBlank() }.toMutableSet()
            packages.addAll(ClassWatcherService.DEFAULT_ALLOWED_PACKAGES)
            ClassWatcherService.allowedPackages = packages
        }
    }

    private fun startWebSocketServer() {
        webSocketServer = AgentWebSocketServer(8080, this) { command, conn ->
            android.util.Log.d("WebSocket", "명령 처리: $command")
            val trimmedCommand = command.trim()
            when {
                trimmedCommand == "START" -> {
                    ClassWatcherService.isClassInSession = true
                    ClassWatcherService.classStartedAtMs = System.currentTimeMillis()
                }
                trimmedCommand == "STOP" -> {
                    ClassWatcherService.isClassInSession = false
                    ClassWatcherService.classStartedAtMs = 0L
                    // Ensure banner isn't left over the student's screen after class.
                    startService(Intent(this, OverlayService::class.java).apply {
                        action = "HIDE"
                    })
                }
                trimmedCommand == "EDIT_APPROVED" -> {
                    sendBroadcast(Intent("com.teaveloper.classroomagent.EDIT_APPROVED"))
                }
                trimmedCommand == "EDIT_REJECTED" -> {
                    sendBroadcast(Intent("com.teaveloper.classroomagent.EDIT_REJECTED"))
                }
                trimmedCommand.startsWith("SET_ALLOWED_APPS") -> {
                    val parts = trimmedCommand.split("|")
                    val newPackages = parts.drop(1).filter { it.isNotBlank() }.toMutableSet()
                    newPackages.addAll(ClassWatcherService.DEFAULT_ALLOWED_PACKAGES)
                    ClassWatcherService.allowedPackages = newPackages
                    getSharedPreferences("setup", MODE_PRIVATE).edit()
                        .putString("allowed_apps", newPackages.joinToString(","))
                        .apply()
                }
            }
        }
        webSocketServer?.start()
    }

    private fun registerMdns() {
        val deviceName = getSharedPreferences("setup", MODE_PRIVATE)
            .getString("deviceName", null)
            ?: android.os.Build.MODEL

        android.util.Log.d("mDNS", "기기 이름: $deviceName")
        ownServiceName = deviceName
        PeerIdentity.myName = deviceName

        if (nsdManager == null) {
            nsdManager = getSystemService(NSD_SERVICE) as android.net.nsd.NsdManager
        }

        val serviceInfo = android.net.nsd.NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = "_classroomagent._tcp."
            port = 8080
            // Advertise our signing pubkey so peers can verify our gossip.
            setAttribute("pk", PeerIdentity.publicKeyB64())
            // Advertise class id so peers filter cross-class gossip.
            if (PeerIdentity.myClassId.isNotEmpty()) {
                setAttribute("cls", PeerIdentity.myClassId)
            }
        }

        nsdListener = object : android.net.nsd.NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: android.net.nsd.NsdServiceInfo) {
                android.util.Log.d("mDNS", "등록 완료: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: android.net.nsd.NsdServiceInfo, code: Int) {
                android.util.Log.e("mDNS", "등록 실패: $code")
            }
            override fun onServiceUnregistered(info: android.net.nsd.NsdServiceInfo) {
                android.util.Log.d("mDNS", "등록 해제됨")
                if (pendingReregister) {
                    pendingReregister = false
                    registerMdns()
                }
            }
            override fun onUnregistrationFailed(info: android.net.nsd.NsdServiceInfo, code: Int) {
                android.util.Log.e("mDNS", "등록 해제 실패: $code")
                pendingReregister = false
                registerMdns()
            }
        }

        nsdManager?.registerService(serviceInfo, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, nsdListener!!)
    }

    /**
     * Discover other classroom agents on the LAN via mDNS and populate
     * PeerRegistry. Own advertisement is filtered out by serviceName match.
     */
    private fun discoverPeers() {
        if (nsdManager == null) {
            nsdManager = getSystemService(NSD_SERVICE) as android.net.nsd.NsdManager
        }
        val mgr = nsdManager ?: return

        val resolveListener = object : android.net.nsd.NsdManager.ResolveListener {
            override fun onResolveFailed(info: android.net.nsd.NsdServiceInfo, code: Int) {
                android.util.Log.w("Peer", "resolve 실패 ${info.serviceName}: $code")
            }
            override fun onServiceResolved(info: android.net.nsd.NsdServiceInfo) {
                val host = info.host?.hostAddress ?: return
                val pk = info.attributes?.get("pk")?.let { String(it, Charsets.UTF_8) }
                val cls = info.attributes?.get("cls")?.let { String(it, Charsets.UTF_8) }
                PeerRegistry.upsert(info.serviceName, host, info.port, pk, cls)
                android.util.Log.d(
                    "Peer",
                    "발견: ${info.serviceName} @ $host:${info.port} cls=$cls (총 ${PeerRegistry.size()})"
                )
            }
        }

        discoveryListener = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                android.util.Log.d("Peer", "discovery 시작: $serviceType")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                android.util.Log.d("Peer", "discovery 중지")
            }
            override fun onStartDiscoveryFailed(serviceType: String, code: Int) {
                android.util.Log.e("Peer", "discovery 시작 실패: $code")
            }
            override fun onStopDiscoveryFailed(serviceType: String, code: Int) {
                android.util.Log.e("Peer", "discovery 중지 실패: $code")
            }
            override fun onServiceFound(info: android.net.nsd.NsdServiceInfo) {
                if (info.serviceName == ownServiceName) return
                // Each resolve requires its own listener instance per NsdManager contract.
                mgr.resolveService(info, resolveListener)
            }
            override fun onServiceLost(info: android.net.nsd.NsdServiceInfo) {
                PeerRegistry.remove(info.serviceName)
                android.util.Log.d("Peer", "소실: ${info.serviceName} (남은 ${PeerRegistry.size()})")
            }
        }

        try {
            mgr.discoverServices(
                "_classroomagent._tcp.",
                android.net.nsd.NsdManager.PROTOCOL_DNS_SD,
                discoveryListener!!
            )
        } catch (e: Exception) {
            android.util.Log.e("Peer", "discovery 시작 예외: ${e.message}")
        }
    }

    private fun reregisterMdns() {
        val mgr = nsdManager
        val listener = nsdListener
        if (mgr != null && listener != null) {
            pendingReregister = true
            try {
                mgr.unregisterService(listener)
            } catch (e: Exception) {
                android.util.Log.e("mDNS", "등록 해제 예외: ${e.message}")
                pendingReregister = false
                registerMdns()
            }
        } else {
            registerMdns()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "SHOW" -> showOverlay(
                reason = intent.getStringExtra("reason") ?: "PENDING",
                count = intent.getIntExtra("count", -1),
                threshold = intent.getIntExtra("threshold", -1)
            )
            "HIDE" -> hideOverlay()
            "RE_REGISTER_MDNS" -> reregisterMdns()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            android.util.Log.w("Peer", "discovery 정리 실패: ${e.message}")
        }
        PeerGossip.shutdown()
        PeerRegistry.clear()
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

    private fun showOverlay(reason: String, count: Int = -1, threshold: Int = -1) {
        android.util.Log.d("OverlayService", "showOverlay: $reason count=$count/$threshold")
        val text = overlayTextFor(reason, count, threshold)
        // If already shown, just update text — keeps banner alive across app switches.
        val existing = overlayView
        if (existing != null) {
            existing.text = text
            existing.setBackgroundColor(overlayBgFor(reason))
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
        }
        overlayView = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(overlayBgFor(reason))
            gravity = Gravity.CENTER
            setPadding(24, 32, 24, 32)
        }
        windowManager.addView(overlayView, params)
    }

    private fun overlayTextFor(reason: String, count: Int, threshold: Int): String = when (reason) {
        "DENIED" -> "이 앱은 사용할 수 없습니다 · 교사 제한"
        else -> if (count >= 0 && threshold > 0) {
            "이 앱은 합의 대기 중 ($count/$threshold 명) · 함께 쓰면 자동 허용"
        } else {
            "이 앱은 아직 합의되지 않았습니다 · 같은 반이 함께 쓰면 자동 허용"
        }
    }

    private fun overlayBgFor(reason: String): Int = when (reason) {
        "DENIED" -> Color.argb(230, 180, 30, 30)   // red band for hard block
        else -> Color.argb(210, 30, 30, 30)         // dim charcoal for soft warning
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