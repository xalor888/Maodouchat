package com.maodouchat.ui

/**
 * P08：App 锁与假聊天表面门闩（从 MainActivity 抽出）。
 *
 * Activity 只持有 Compose 可变状态并渲染对应 Screen；决策与后台记账经本控制器，
 * 不持有 Activity 引用。窗口隐私仍由 [com.maodouchat.security.WindowPrivacyController] 负责。
 */
class AppSurfaceGateController(
    private val shouldShowFake: () -> Boolean,
    private val shouldLock: () -> Boolean,
    private val noteAppLockBackground: () -> Unit,
    private val noteFakeChatBackground: () -> Unit,
    private val markFakeUnlocked: () -> Unit,
    private val markAppUnlocked: () -> Unit,
    private val isAppLockEnabled: () -> Boolean,
    private val isFakeChatEnabled: () -> Boolean,
    private val isScreenSecureEnabled: () -> Boolean,
    private val onSecretChatSurface: () -> Boolean,
    private val hasActiveSecretSurface: () -> Boolean,
) {
    data class State(
        val showFakeChat: Boolean = false,
        val showAppLock: Boolean = false,
    )

    fun initialState(): State = State(
        showFakeChat = shouldShowFake(),
        showAppLock = shouldLock(),
    )

    /**
     * @return 是否应在后台保持窗口安全（FLAG_SECURE）。
     */
    fun onHostPaused(showAppLock: Boolean): Boolean {
        // 系统认证会暂停 Activity；锁屏已显示时不能把认证弹窗误记成普通后台离开。
        if (!showAppLock) noteAppLockBackground()
        // 假聊天模式启用即视为「离开」：回前台需重新走假界面拦截
        noteFakeChatBackground()
        return isAppLockEnabled() ||
            isFakeChatEnabled() ||
            isScreenSecureEnabled() ||
            onSecretChatSurface() ||
            hasActiveSecretSurface()
    }

    fun onHostResumed(current: State): State {
        var showFakeChat = current.showFakeChat
        var showAppLock = current.showAppLock
        if (!showFakeChat && shouldShowFake()) {
            showFakeChat = true
        }
        if (!showAppLock) {
            if (shouldLock()) {
                showAppLock = true
            } else {
                markAppUnlocked()
            }
        }
        return State(showFakeChat = showFakeChat, showAppLock = showAppLock)
    }

    /** 假聊天解锁后：先关闭假界面，再按需进入 App 锁。 */
    fun onFakeChatUnlocked(): State {
        markFakeUnlocked()
        return if (shouldLock()) {
            State(showFakeChat = false, showAppLock = true)
        } else {
            markAppUnlocked()
            State(showFakeChat = false, showAppLock = false)
        }
    }

    fun onAppLockUnlocked(current: State): State {
        markAppUnlocked()
        return current.copy(showAppLock = false)
    }
}
