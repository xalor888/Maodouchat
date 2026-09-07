package com.maodouchat.ui

/**
 * P08：应用前台/后台与 presence 派发（从 MainActivity 抽出）。
 *
 * Activity 只转发生命周期；本类负责 `appInForeground` / `activeChatId` 清理与 presence 唤醒，
 * 不持有 Activity 引用。presence 经注入，避免 UI 宿主直连 WebSocketClient。
 */
class AppForegroundLifecycleController(
    private val setAppInForeground: (Boolean) -> Unit,
    private val clearActiveChatSurface: () -> Unit,
    private val sendPresence: (Boolean) -> Unit,
) {
    fun onHostCreated() {
        setAppInForeground(true)
    }

    /**
     * App 真正离开前台（onStop，非权限弹窗/指纹认证等瞬时遮挡）。
     * 清空 activeChatId 以便后台弹托盘；保留 openChatDetailId 供列表解密门禁。
     */
    fun onHostStopped() {
        clearActiveChatSurface()
        setAppInForeground(false)
        sendPresence(false)
    }

    fun onHostStarted() {
        setAppInForeground(true)
        sendPresence(true)
    }
}
