package com.maodouchat.security

import android.app.Activity
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Screenshot / screen-record capture detector via MediaStore observers.
 *
 * Used for:
 * - Secret chat: notify local user (and optionally peer via chat system event)
 * - Disappearing messages: Telegram-style capture warning
 *
 * Limits:
 * - ContentObserver based; ~1s latency
 * - Third-party capture apps writing custom paths may be missed
 * - FLAG_SECURE blocks system screenshots; this is a bypass complement
 */
class ScreenshotDetector(
    private val context: Context,
    private val onScreenshotDetected: () -> Unit
) {
    private var imageObserver: ContentObserver? = null
    private var videoObserver: ContentObserver? = null
    @Volatile private var active = false
    private val lastEmitAt = AtomicLong(0L)
    private var screenCaptureCallback: Any? = null

    fun start() {
        if (active) return
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
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            imageObserver ?: return
        )
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            videoObserver ?: return
        )
        registerScreenCaptureCallback()
        Log.i(TAG, "Screenshot/screen-record detector started")
    }

    fun stop() {
        active = false
        unregisterScreenCaptureCallback()
        imageObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        videoObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        imageObserver = null
        videoObserver = null
    }

    /** API 34+: system screenshot callback (complements MediaStore path sniffing). */
    private fun registerScreenCaptureCallback() {
        if (Build.VERSION.SDK_INT < 34) return
        val activity = context.findActivity() ?: return
        runCatching {
            val cb = Activity.ScreenCaptureCallback { emitCapture() }
            activity.registerScreenCaptureCallback(activity.mainExecutor, cb)
            screenCaptureCallback = cb
            Log.i(TAG, "ScreenCaptureCallback registered")
        }.onFailure { Log.w(TAG, "ScreenCaptureCallback unavailable", it) }
    }

    private fun unregisterScreenCaptureCallback() {
        if (Build.VERSION.SDK_INT < 34) return
        val activity = context.findActivity() ?: return
        val cb = screenCaptureCallback as? Activity.ScreenCaptureCallback ?: return
        runCatching { activity.unregisterScreenCaptureCallback(cb) }
        screenCaptureCallback = null
    }

    private fun inspectCapture(uri: Uri?, images: Boolean) {
        try {
            val nowSec = System.currentTimeMillis() / 1000L
            if (images) {
                checkImage(uri, nowSec)
            } else {
                checkVideo(uri, nowSec)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Capture check failed", e)
        }
    }

    private fun checkImage(uri: Uri?, nowSec: Long) {
        val target = uri ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.Images.Media.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            projection += MediaStore.Images.Media.DATA
        }
        context.contentResolver.query(target, projection.toTypedArray(), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return
                val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)).orEmpty()
                if (!mimeType.startsWith("image/")) return
                val pathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    @Suppress("DEPRECATION")
                    cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                }
                val path = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else ""
                val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                if (nowSec - dateAdded > 5) return
                if (isScreenshotPath(path, name)) emitCapture()
            }
    }

    private fun checkVideo(uri: Uri?, nowSec: Long) {
        val target = uri ?: MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.MIME_TYPE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.Video.Media.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            projection += MediaStore.Video.Media.DATA
        }
        context.contentResolver.query(target, projection.toTypedArray(), null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return
                val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED))
                val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)).orEmpty()
                if (!mimeType.startsWith("video/")) return
                val pathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                } else {
                    @Suppress("DEPRECATION")
                    cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                }
                val path = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else ""
                val nameIdx = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                if (nowSec - dateAdded > 8) return
                if (isScreenRecordPath(path, name)) emitCapture()
            }
    }

    private fun emitCapture() {
        val now = System.currentTimeMillis()
        val prev = lastEmitAt.get()
        if (now - prev < 2500L) return
        if (!lastEmitAt.compareAndSet(prev, now)) return
        onScreenshotDetected()
    }

    companion object {
        private const val TAG = "ScreenshotDetector"

        /**
         * Secret chat always runs the detector (bypass complement). The
         * SCREENSHOT_DETECT flag only gates non-secret disappearing-message warnings.
         */
        fun shouldStart(secretActive: Boolean, screenshotDetectFlag: Boolean): Boolean =
            secretActive || screenshotDetectFlag

        fun isScreenshotPath(path: String, name: String = ""): Boolean {
            val p = path.lowercase()
            val n = name.lowercase()
            return p.contains("screenshot") ||
                p.contains("screen_shot") ||
                p.contains("screen-shot") ||
                p.contains("截屏") ||
                p.contains("截图") ||
                n.startsWith("screenshot") ||
                n.contains("screenshot") ||
                n.startsWith("screen_") ||
                n.contains("截屏") ||
                n.contains("截图")
        }

        fun isScreenRecordPath(path: String, name: String = ""): Boolean {
            val p = path.lowercase()
            val n = name.lowercase()
            return p.contains("screenrecord") ||
                p.contains("screen_record") ||
                p.contains("screen-record") ||
                p.contains("screen recordings") ||
                p.contains("screenrecording") ||
                p.contains("录屏") ||
                n.contains("screenrecord") ||
                n.contains("screen_record") ||
                n.contains("record") && (n.contains("screen") || n.contains("scr")) ||
                n.contains("录屏")
        }
    }
}
