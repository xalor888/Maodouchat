package com.maodouchat.security

import android.os.Build
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import com.maodouchat.util.RuntimeFlags

/**
 * 窗口隐私控制（P08：自 `MainActivity` 逐字迁出）。
 *
 * 按当前表面状态（App 锁/假聊天/会话/密聊/PIN 锁）经 [ScreenSecurePolicy]
 * 判定 FLAG_SECURE 并落到 window；OEM 忽略 FLAG_SECURE 时起截图 scrubber 兜底。
 * 密聊/PIN 表面状态由本类持有；App 锁/假聊天/会话状态由宿主以 lambda 供给。
 */
class WindowPrivacyController(
    private val activity: FragmentActivity,
    private val showAppLock: () -> Boolean,
    private val showFakeChat: () -> Boolean,
    private val onChatSurface: () -> Boolean,
) {
    @Volatile var onSecretChatSurface = false
    @Volatile var onChatLockSurface = false
    @Volatile private var windowSecureRequested = false
    private var captureScrubber: ScreenshotDetector? = null

    fun refreshWindowPrivacy() {
        val secure = ScreenSecurePolicy.shouldSecureWindow(
            appLockShowing = showAppLock(),
            globalEnabled = ScreenSecureManager.isEnabled(activity),
            onChatSurface = onChatSurface(),
            secretChatSurfaceActive = onSecretChatSurface || SecretChatSession.hasActiveSecretSurface(),
            chatLockSurfaceActive = onChatLockSurface
        ) || showFakeChat()
        updateWindowPrivacy(secure)
    }

    /** Detail screens call this after toggling 密聊 so FLAG_SECURE updates without re-nav. */
    fun notifySecretChatSurfaceChanged(chatId: String, isSecret: Boolean) {
        if (isSecret) {
            SecretChatSession.markSurfaceActive(chatId)
        } else {
            // 只放 FLAG_SECURE 标记。真正删解密缓存由 disable / logout / SIM 路径承担，
            // 避免 ChatDetail 在 isSecretChat 尚未查完时把密聊误降成 false 并烧掉媒体。
            SecretChatSession.clearSurfaceMarker(chatId)
        }
        onSecretChatSurface = SecretChatSession.hasActiveSecretSurface()
        refreshWindowPrivacy()
    }

    /**
     * ChatDetail leaving composition (two-pane deselect / back). Drops FLAG_SECURE markers
     * without deleting decrypted media — disk clear stays with disable / logout / SIM.
     */
    fun notifySecretChatSurfaceLeft(chatId: String) {
        SecretChatSession.clearSurfaceMarker(chatId)
        onSecretChatSurface = SecretChatSession.hasActiveSecretSurface()
        refreshWindowPrivacy()
    }

    /** ChatLockGate 显示/隐藏时调用：PIN 输入期间强制 FLAG_SECURE，即便全局开关关闭、也非密聊。 */
    fun notifyChatLockSurfaceChanged(active: Boolean) {
        onChatLockSurface = active
        refreshWindowPrivacy()
    }

    fun notifyScreenSecurePreferenceChanged() {
        refreshWindowPrivacy()
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) refreshWindowPrivacy()
    }

    /** Activity 销毁时停掉截图 scrubber（防泄漏）。 */
    fun shutdown() {
        captureScrubber?.stop()
        captureScrubber = null
    }

    internal fun updateWindowPrivacy(secure: Boolean) {
        windowSecureRequested = secure
        // addFlags 不够：enableEdgeToEdge / 部分 OEM 会覆盖 LayoutParams.flags。
        // 必须同时写 window.attributes，并在下一帧再钉一次。
        applySecureFlagToWindow(activity.window, secure)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { activity.setRecentsScreenshotEnabled(!secure) }
        }
        activity.window.decorView.post { applySecureFlagToWindow(activity.window, windowSecureRequested) }
        syncCaptureScrubber(secure)
        // Hide task snapshot in recents while secure surfaces are active (secret / chat lock).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            runCatching {
                val am = activity.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val hideRecents = secure && RuntimeFlags.isEnabled(activity, RuntimeFlags.RECENTS_EXCLUSION)
                am.appTasks.firstOrNull()?.setExcludeFromRecents(hideRecents)
            }
        }
    }

    private fun applySecureFlagToWindow(target: android.view.Window, secure: Boolean) {
        val attrs = target.attributes
        val next = if (secure) {
            attrs.flags or WindowManager.LayoutParams.FLAG_SECURE
        } else {
            attrs.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        }
        if (attrs.flags != next) {
            attrs.flags = next
            target.attributes = attrs
        }
        if (secure) target.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else target.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /**
     * FLAG_SECURE 被部分 OEM 忽略时，系统仍可能把截图写进相册。
     * 全局防截屏 / 密聊开启时立刻删掉刚写入的截图/录屏文件。
     */
    private fun syncCaptureScrubber(secure: Boolean) {
        if (secure) {
            if (captureScrubber == null) {
                captureScrubber = ScreenshotDetector(activity) {
                    SecureCaptureScrubber.deleteLatestCapture(activity)
                }.also { it.start() }
            }
        } else {
            captureScrubber?.stop()
            captureScrubber = null
        }
    }
}
