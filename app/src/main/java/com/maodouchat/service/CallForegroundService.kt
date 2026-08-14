package com.maodouchat.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.maodouchat.MainActivity
import com.maodouchat.R
import com.maodouchat.security.AppLockManager
import com.maodouchat.call.CallActionBus

/**
 * 通话前台服务 — 把当前音视频通话提升为前台服务，避免后台时 WebRTC / 信令被系统休眠切断。
 *
 * 启动方式：`CallForegroundService.start(context, contactName, isVideo, callId)`
 * 结束方式：`CallForegroundService.stop(context)`
 *
 * 使用 START_NOT_STICKY：因为通话结束必须由用户主动触发，系统拉起挂断会破坏 UX。
 * 同时启动时使用 `foregroundServiceType` —— Android 14+ 要求音频通话使用 `microphone`、视频通话使用 `microphone|camera`。
 */
class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统/OEM 在无有效 intent 下重建服务（START_NOT_STICKY 下罕见）：无真实通话，
        // 不构建空通知、不提升前台，直接退出，避免悬挂的"未知来电"空通知。
        if (intent == null) return START_NOT_STICKY
        if (intent.action == ACTION_HANG_UP) {
            val requestedCallId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
            if (requestedCallId != activeCallId) return START_NOT_STICKY
            CallActionBus.requestHangUp(requestedCallId)
            stopSelf()
            return START_NOT_STICKY
        }
        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: getString(R.string.call_unknown_caller)
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        activeCallId = callId

        ensureChannel()

        // 点击通知回到通话
        val tapIntent = Intent(this, MainActivity::class.java)
        tapIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        tapIntent.putExtra("openCall", true)
        val pendingFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val contentPi = PendingIntent.getActivity(this, 0, tapIntent, pendingFlags)
        val hangUpIntent = Intent(this, CallForegroundService::class.java).apply {
            action = ACTION_HANG_UP
            putExtra(EXTRA_CALL_ID, callId)
        }
        val hangUpPi = PendingIntent.getService(this, 1, hangUpIntent, pendingFlags)

        val privateSubtitle = getString(R.string.call_active_private_subtitle)
        val subtitle = if (AppLockManager.isEnabled(this)) privateSubtitle
        else getString(if (isVideo) R.string.call_active_video_subtitle else R.string.call_active_audio_subtitle, contactName)
        val publicNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.call_active_title))
            .setContentText(privateSubtitle)
            .build()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.call_active_title))
            .setContentText(subtitle)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(contentPi)
            .addAction(R.drawable.ic_notification, getString(R.string.call_hang_up), hangUpPi)
            .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val type = if (isVideo) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIFICATION_ID, notification, type)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, 0)
            } else {
                // 8.44：API <29 的 startForeground 无 tag 重载——用独立高位 id 拉开与
                // 来电/未接/动态互动 hashCode id 的碰撞空间（这些 id 通常 < 2^31 的低位区）
                startForeground(NOTIFICATION_ID_HIGH, notification)
            }
            START_NOT_STICKY
        } catch (_: Throwable) {
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        activeCallId = ""
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                nm.cancel(NOTIFICATION_TAG, NOTIFICATION_ID)
            } else {
                nm.cancel(NOTIFICATION_ID_HIGH)
            }
        }
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.call_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.call_channel_description)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    companion object {
        @Volatile private var activeCallId: String = ""
        const val CHANNEL_ID = "call_active"
        // 8.44：前台通话通知用独立 tag（API 29+）；pre-Q 用远离 hashCode 低位区的高位 id
        const val NOTIFICATION_TAG = "maodouchat_call_active"
        const val NOTIFICATION_ID = 9001
        const val NOTIFICATION_ID_HIGH = 0x444F5543
        const val EXTRA_CONTACT_NAME = "extra_contact_name"
        const val EXTRA_IS_VIDEO = "extra_is_video"
        const val EXTRA_CALL_ID = "extra_call_id"
        const val ACTION_HANG_UP = "com.maodouchat.action.HANG_UP_CALL"

        fun start(context: Context, contactName: String, isVideo: Boolean, callId: String) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                putExtra(EXTRA_CONTACT_NAME, contactName)
                putExtra(EXTRA_IS_VIDEO, isVideo)
                putExtra(EXTRA_CALL_ID, callId)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Throwable) {
                // 权限被拒或厂商限制：通话仍可进行，但进程可能被回收
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, CallForegroundService::class.java))
            } catch (_: Throwable) { /* noop */ }
        }

        /** 当前前台通话 callId；空串表示无活跃通话（供 IncomingCallObserver 转发 hang-up） */
        fun getActiveCallId(): String = activeCallId
    }
}
