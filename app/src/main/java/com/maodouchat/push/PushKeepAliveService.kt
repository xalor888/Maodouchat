package com.maodouchat.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.maodouchat.MainActivity
import com.maodouchat.R
import com.maodouchat.network.ApiConfig
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 9.3xx：后台推送保活前台服务（Ideaura KeepAliveService 同款移植）：
 *
 * - 前台服务类型 dataSync/mediaPlayback（按当前保活模式），START_STICKY
 * - PARTIAL_WAKE_LOCK + WIFI_MODE_FULL_HIGH_PERF WifiLock
 * - 网络变化回调：bindProcessToNetwork + 可用时立即重连 WebSocket
 * - 低优先级常驻通知
 * - 模式 media：挂 MediaSession「播放中」+ 静音循环（音乐播放器伪装）
 * - 模式 call：挂 onHold 假来电（通话级优先级伪装）
 * - 被系统销毁时启动守护服务（PushDaemonService）互相复活
 */
class PushKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var mediaKeepAlive: MediaKeepAlive? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate mode=${PushKeepAliveModeStore.mode(this)}")

        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Maodouchat:PushKeepAlive")
        }
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Maodouchat:PushWifiLock")
        }
        wakeLock?.acquire(10 * 60 * 1000L)
        wifiLock?.acquire()

        createChannel()
        startAsForeground()

        // Ideaura 同款：网络变化 → 绑定网络 + 重连推送长连接
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "network available, rebinding + reconnect ws")
                    runCatching {
                        connectivityManager?.bindProcessToNetwork(network)
                    }
                    ensureWebSocket()
                }

                override fun onLost(network: Network) {
                    Log.i(TAG, "network lost")
                }
            }
            runCatching {
                connectivityManager?.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    networkCallback!!
                )
            }
        }
        applyStrategy()
        ensureWebSocket()
        // 9.3xx：策略自愈循环——假来电被系统按未接来电超时挂断 / 媒体会话被系统回收时，
        // 每 30s 检测并按当前模式重挂，保证保活不静默失效
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000L)
                if (PushKeepAliveModeStore.wantsFakeCall(this@PushKeepAliveService) &&
                    !FakeCallKeepAlive.isActive()
                ) {
                    FakeCallKeepAlive.addFakeCall(this@PushKeepAliveService)
                }
                if (PushKeepAliveModeStore.wantsMedia(this@PushKeepAliveService) &&
                    MediaKeepAlive.active == null
                ) {
                    mediaKeepAlive = mediaKeepAlive ?: MediaKeepAlive(this@PushKeepAliveService)
                    mediaKeepAlive?.start()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次被系统拉起（进程被杀后 START_STICKY 重建）都重新校验模式：
        // 用户已关闭/已登出则不再前台，避免幽灵保活
        if (!PushKeepAliveModeStore.isEnabled(this) ||
            TokenManager.getInstance(this).getToken().isNullOrBlank()
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureWebSocket()
        // 每次 onStartCommand 都重校验策略：真实通话结束后（onRealCallEnded 触发重启）
        // 或模式切换后，媒体会话/假来电按当前模式恢复
        applyStrategy()
        return START_STICKY
    }

    /** 按模式挂策略（媒体会话 / 假来电）。 */
    private fun applyStrategy() {
        when {
            PushKeepAliveModeStore.wantsMedia(this) -> {
                // 前台类型切到 mediaPlayback（媒体豁免），再挂静音循环
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching {
                        startForeground(
                            NOTIFICATION_ID,
                            buildNotification(),
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        )
                    }
                }
                mediaKeepAlive = mediaKeepAlive ?: MediaKeepAlive(this)
                mediaKeepAlive?.start()
            }
            PushKeepAliveModeStore.wantsFakeCall(this) -> {
                FakeCallKeepAlive.addFakeCall(this)
            }
        }
    }

    /** 保活服务拉起时确保 WS 连接（登录态才有意义）。 */
    private fun ensureWebSocket() {
        scope.launch {
            val token = TokenManager.getInstance(this@PushKeepAliveService).getToken()
            if (token.isNullOrBlank()) {
                Log.w(TAG, "no token; stopping keepalive")
                stopSelf()
                return@launch
            }
            if (!WebSocketClient.isConnected()) {
                Log.i(TAG, "connecting ws from keepalive service")
                WebSocketClient.connect(ApiConfig.WS_URL, token)
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.push_keepalive_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.push_keepalive_channel_desc)
            setShowBadge(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        // 9.4xx：任何失败（后台启动限制 ForegroundServiceStartNotAllowedException /
        // FGS 类型权限 SecurityException）都不允许带崩进程——降级重试一次无类型版本
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (PushKeepAliveModeStore.wantsMedia(this)) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            val ok = runCatching { startForeground(NOTIFICATION_ID, notification, type) }.isSuccess
            if (!ok) {
                Log.w(TAG, "typed startForeground failed; falling back to untyped")
                runCatching { startForeground(NOTIFICATION_ID, notification) }
            }
        } else {
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.push_keepalive_notification_title))
            .setContentText(getString(R.string.push_keepalive_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tapIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
        mediaKeepAlive?.stop()
        mediaKeepAlive = null
        FakeCallKeepAlive.removeFakeCall()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        networkCallback?.let { runCatching { connectivityManager?.unregisterNetworkCallback(it) } }
        runCatching { connectivityManager?.bindProcessToNetwork(null) }
        // Ideaura 同款：被销毁时拉起守护服务互相复活
        // （用户主动关闭/登出时 PushKeepAlive.stop 会先清模式，守护服务启动后自行退出）
        runCatching { startService(Intent(this, PushDaemonService::class.java)) }
    }

    companion object {
        const val TAG = "PushKeepAlive"
        const val CHANNEL_ID = "push_keepalive"
        const val NOTIFICATION_ID = 0x4B414C56 // "KALV"
    }
}
