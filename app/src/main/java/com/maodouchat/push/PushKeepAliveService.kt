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
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Visible foreground owner for the authenticated push WebSocket transport.
 *
 * Stored legacy keepalive modes remain accepted for preference compatibility, but every enabled
 * mode now uses the same data-sync foreground service. No silent media or synthetic Telecom call
 * is created. The process/network locks and daemon resurrection retain their existing behavior.
 */
class PushKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private lateinit var pushTransport: PushTransport

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 仅在成功进前台且登录态有效时允许 onDestroy 拉 daemon，避免后台 FGS 崩溃环。 */
    @Volatile
    private var allowDaemonResurrection = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate mode=${PushKeepAliveModeStore.mode(this)}")

        createChannel()
        pushTransport = androidPushTransport(this)
        // startForegroundService contract: promote within five seconds before validating the session.
        val promoted = startAsForeground()
        val shouldRun = PushKeepAlivePolicy.shouldStartService(
            PushKeepAliveModeStore.mode(this),
            !TokenManager.getInstance(this).getToken().isNullOrBlank(),
        )
        if (!promoted || !shouldRun) {
            Log.w(TAG, "keepalive not eligible (promoted=$promoted); stopSelf")
            allowDaemonResurrection = false
            stopSelf()
            return
        }
        allowDaemonResurrection = true

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

        // Ideaura 同款：网络变化 → 绑定网络 + 重连推送长连接
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "network available, rebinding + reconnect ws")
                    runCatching {
                        connectivityManager?.bindProcessToNetwork(network)
                    }
                    when (pushTransport.onNetworkAvailable()) {
                        PushTransportState.NoSession -> {
                            allowDaemonResurrection = false
                            stopSelf()
                        }
                        else -> Unit
                    }
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
        ensureTransport()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次被系统拉起（进程被杀后 START_STICKY 重建）都重新校验模式：
        // 用户已关闭/已登出则不再前台，避免幽灵保活
        if (!PushKeepAlivePolicy.shouldStartService(
                PushKeepAliveModeStore.mode(this),
                !TokenManager.getInstance(this).getToken().isNullOrBlank(),
            )
        ) {
            allowDaemonResurrection = false
            stopSelf()
            return START_NOT_STICKY
        }
        ensureTransport()
        return START_STICKY
    }

    private fun ensureTransport() {
        scope.launch {
            when (pushTransport.ensureForegroundConnection()) {
                PushTransportState.NoSession -> {
                    Log.w(TAG, "no active session; stopping push transport")
                    allowDaemonResurrection = false
                    stopSelf()
                }
                is PushTransportState.Connecting -> Log.i(TAG, "connecting foreground push transport")
                is PushTransportState.Connected -> Unit
                PushTransportState.Stopped -> Unit
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

    /** @return true 已进入前台；false 两次都失败，调用方必须 stopSelf 且禁止拉 daemon。 */
    private fun startAsForeground(): Boolean {
        val notification = buildNotification()
        // 后台启动限制 / FGS 类型权限失败不得带崩进程；全失败则放弃保活。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            val ok = runCatching { startForeground(NOTIFICATION_ID, notification, type) }.isSuccess
            if (ok) return true
            Log.w(TAG, "typed startForeground failed; falling back to untyped")
            val fallback = runCatching { startForeground(NOTIFICATION_ID, notification) }.isSuccess
            if (!fallback) {
                Log.w(TAG, "untyped startForeground also failed")
            }
            return fallback
        }
        return runCatching { startForeground(NOTIFICATION_ID, notification) }.isSuccess
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
        pushTransport.stop()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        networkCallback?.let { runCatching { connectivityManager?.unregisterNetworkCallback(it) } }
        runCatching { connectivityManager?.bindProcessToNetwork(null) }
        val shouldResurrect = allowDaemonResurrection &&
            !PushKeepAlive.suppressResurrection &&
            PushKeepAlivePolicy.shouldResurrectDaemon(
                PushKeepAliveModeStore.mode(this),
                !TokenManager.getInstance(this).getToken().isNullOrBlank(),
            )
        if (shouldResurrect) {
            runCatching { startService(Intent(this, PushDaemonService::class.java)) }
        } else {
            Log.i(TAG, "skip daemon resurrection (logout/off/no token)")
        }
    }

    companion object {
        const val TAG = "PushKeepAlive"
        const val CHANNEL_ID = "push_keepalive"
        const val NOTIFICATION_ID = 0x4B414C56 // "KALV"
    }
}
