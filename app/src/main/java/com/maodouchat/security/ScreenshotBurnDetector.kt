package com.maodouchat.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.maodouchat.util.MediaCache
import com.maodouchat.util.SecretScreenshotBurnPrefs
import java.util.concurrent.atomic.AtomicLong

/**
 * 密聊「截屏即焚」检测器（B2 surface · 截屏即焚，health 名 burnz）。
 *
 * 复用 [ScreenshotDetector] 的 MediaStore 嗅探 + API 34 ScreenCaptureCallback 双通道，
 * 但行为不同：一旦检测到截屏/录屏尝试，立即焚毁当前活动密聊会话的本地解密缓存媒体
 * （[MediaCache.deleteSecretChatMedia]），并通过 [onBurned] 通知 UI 提示。
 *
 * 与 [ScreenshotDetector] 并存：前者负责「告警 + 对端 CAPTURE_ALERT」，
 * 后者负责「即焚」——只清理本机解密缓存，不触碰服务端（服务端只有密文）。
 *
 * 限制与 ScreenshotDetector 相同：ContentObserver ~1s 延迟；外置相机 / 无权限路径可能漏检；
 * FLAG_SECURE 已挡住系统截屏，本类是绕过路径的兜底。
 */
class ScreenshotBurnDetector(
    private val context: Context,
    private val activeChatIds: () -> Set<String> = { SecretChatSession.activeSecretSurfaceChatIds() },
    private val onBurned: (chatIds: Set<String>) -> Unit = {}
) {
    private var imageObserver: ContentObserver? = null
    private var videoObserver: ContentObserver? = null
    private var screenCaptureCallback: Activity.ScreenCaptureCallback? = null
    @Volatile private var active = false
    private val lastBurnAt = AtomicLong(0L)

    fun start() {
        if (active) return
        if (!SecretScreenshotBurnPrefs.isEnabled(context)) return
        active = true
        val handler = Handler(Looper.getMainLooper())
        imageObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, changedUri: Uri?) {
                if (!active) return
                inspectCapture(changedUri, images = true)
            }
        }
        videoObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, changedUri: Uri?) {
                if (!active) return
                inspectCapture(changedUri, images = false)
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, imageObserver ?: return
        )
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, videoObserver ?: return
        )
        registerScreenCaptureCallback()
        Log.i(TAG, "Screenshot burn detector started")
    }

    fun stop() {
        active = false
        unregisterScreenCaptureCallback()
        imageObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        videoObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        imageObserver = null
        videoObserver = null
    }

    /** API 34+: 系统截屏回调（与 MediaStore 路径嗅探互补）。 */
    private fun registerScreenCaptureCallback() {
        if (Build.VERSION.SDK_INT < 34) return
        val activity = context.findActivity() ?: return
        runCatching {
            val cb = Activity.ScreenCaptureCallback { burnAll() }
            activity.registerScreenCaptureCallback(activity.mainExecutor, cb)
            screenCaptureCallback = cb
        }.onFailure { Log.w(TAG, "ScreenCaptureCallback unavailable", it) }
    }

    private fun unregisterScreenCaptureCallback() {
        if (Build.VERSION.SDK_INT < 34) return
        val activity = context.findActivity() ?: return
        val cb = screenCaptureCallback ?: return
        runCatching { activity.unregisterScreenCaptureCallback(cb) }
        screenCaptureCallback = null
    }

    private fun Context.findActivity(): Activity? {
        var ctx: Context? = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun inspectCapture(uri: Uri?, images: Boolean) {
        try {
            val nowSec = System.currentTimeMillis() / 1000L
            val projection = mutableListOf(
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                projection += MediaStore.Images.Media.RELATIVE_PATH
            } else {
                @Suppress("DEPRECATION")
                projection += MediaStore.Images.Media.DATA
            }
            val target = if (images) {
                uri ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            } else {
                uri ?: MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            context.contentResolver.query(
                target, projection.toTypedArray(), null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return
                val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                if (nowSec - dateAdded > (if (images) 5 else 8)) return
                val pathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    @Suppress("DEPRECATION")
                    cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                }
                val path = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else ""
                val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                val isCapture = if (images) {
                    ScreenshotDetector.isScreenshotPath(path, name)
                } else {
                    ScreenshotDetector.isScreenRecordPath(path, name)
                }
                if (isCapture) burnAll()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Burn check failed", e)
        }
    }

    /** 焚毁当前全部活动密聊会话的本地解密缓存（去重窗口 2.5s）。 */
    private fun burnAll() {
        val now = System.currentTimeMillis()
        val prev = lastBurnAt.get()
        if (now - prev < 2500L) return
        if (!lastBurnAt.compareAndSet(prev, now)) return
        if (!SecretScreenshotBurnPrefs.isEnabled(context)) return
        val chatIds = activeChatIds().takeIf { it.isNotEmpty() } ?: return
        val purged = chatIds.filter { chatId ->
            MediaCache.deleteSecretChatMedia(context, chatId)
        }
        Log.w(TAG, "Secret screenshot burn: purged ${purged.size} chat caches")
        onBurned(purged.toSet())
    }

    companion object {
        private const val TAG = "ScreenshotBurnDetector"
    }
}
