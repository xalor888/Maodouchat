package com.maodouchat.security

/**
 * SecureSessionManager 发出的 UI 侧复位事件（登出 / 切号 / 删号清理时）。
 *
 * 方向是 security → ui：security 层只发事件、不直接碰 ui 包的单例；
 * UI 侧（如 ChatListViewModel）订阅后复位界面态。
 */
enum class SessionUiResetEvent {
    /** 未读角标归零：避免残留上一账号的计数。 */
    UNREAD_BADGE,
}
