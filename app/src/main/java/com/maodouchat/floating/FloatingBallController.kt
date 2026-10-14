package com.maodouchat.floating

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.net.toUri
import com.maodouchat.MainActivity
import com.maodouchat.R
import java.lang.ref.WeakReference

/**
 * B5 悬浮球（SYSTEM_ALERT_WINDOW）。
 *
 * 设计约束：
 * - 悬浮球本体不展示任何消息/会话内容（纯通用小球），不受密聊/锁定门禁约束；
 * - 点击打开 App 仍走 MainActivity 既有 App 锁 / 假聊天 / 密聊门禁，不绕过任何保护；
 * - 会话失效/账号切换时点击仅拉起 App，由 MainActivity 自行判断导航。
 *
 * 手势优化：
 * - touchSlop 阈值区分「点击」与「拖动」，避免拖动误触打开 App；
 * - 拖动期间按手指增量移动，松手后做短惯性滑行（postDelayed 逐帧衰减）；
 * - 拖到屏幕顶部「回收区」松手 → 移除悬浮球（关闭手势）；
 * - 位置按屏幕宽高百分比持久化，进程重建后恢复。
 */
object FloatingBallController {

    private const val BALL_SIZE_DP = 52
    internal const val RECYCLE_ZONE_RATIO = 0.12f     // 顶部 12% 为回收区

    private var windowManager: WindowManager? = null
    // WindowManager itself keeps a strong reference while the overlay is attached. Holding only a
    // weak reference here prevents the singleton from retaining the View after removal.
    private var ballView: WeakReference<View>? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var gestureEpoch: Long = 0L

    // ---- 状态 ----
    @Synchronized
    fun isShowing(): Boolean = ballView?.get()?.isAttachedToWindow == true

    fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context.applicationContext)

    fun openOverlaySettings(context: Context) {
        runCatching {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${context.packageName}".toUri()
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun isEnabled(context: Context): Boolean = FloatingBallPreferences.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        if (enabled) {
            android.util.Log.w("FloatingBall", "floating ball is retired; refusing enable")
            FloatingBallPreferences.setEnabled(context, false)
            stop(app)
            return
        }
        FloatingBallPreferences.setEnabled(context, false)
        stop(app)
    }

    /** Retired: overlay ball is cut. Callers that still invoke start() are no-ops. */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        android.util.Log.w("FloatingBall", "floating ball is retired; refusing start")
        FloatingBallPreferences.setEnabled(app, false)
        stop(app)
    }

    /** 停止并移除悬浮球 */
    @Synchronized
    fun stop(context: Context) {
        val app = context.applicationContext
        val wm = windowManager ?: runCatching { app.getSystemService(Context.WINDOW_SERVICE) as WindowManager }.getOrNull() ?: return
        val view = ballView?.get()
        if (view == null) {
            windowManager = null
            ballView = null
            layoutParams = null
            return
        }
        invalidateGestures()
        if (view.isAttachedToWindow) {
            runCatching { wm.removeView(view) }
        }
        windowManager = null
        ballView = null
        layoutParams = null
    }

    @Synchronized
    fun toggle(context: Context) {
        if (isShowing()) stop(context) else start(context)
    }

    @Synchronized
    internal fun beginGesture(): Long = ++gestureEpoch

    @Synchronized
    internal fun isCurrentGesture(epoch: Long): Boolean = epoch == gestureEpoch

    @Synchronized
    internal fun invalidateGestures() {
        gestureEpoch++
    }

    // ---- 内部 ----
    internal fun removeByRecycle(context: Context) {
        stop(context)
        Toast.makeText(context.applicationContext, R.string.floating_ball_removed, Toast.LENGTH_SHORT).show()
    }

    internal fun openApp(context: Context) {
        runCatching {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }
    }

    internal fun persistPosition(context: Context, wm: WindowManager, params: WindowManager.LayoutParams) {
        val bounds = boundsOf(wm)
        val w = bounds.width().coerceAtLeast(1)
        val h = bounds.height().coerceAtLeast(1)
        FloatingBallPreferences.setPosition(
            context,
            ((params.x + params.width / 2f) / w).coerceIn(0f, 1f),
            ((params.y + params.height / 2f) / h).coerceIn(0f, 1f),
        )
    }

    private fun circleBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF007AFF.toInt())
            setStroke(2, 0xFFFFFFFF.toInt())
        }

    internal fun boundsOf(wm: WindowManager): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }
}
