package com.maodouchat.security.fakechat

/**
 * 假聊天威胁模型与数据边界守卫。
 *
 * 威胁建模定义：
 * 1. 强迫窥探与搜查胁迫（Coercion / Inspection）：
 *    - 攻击面：攻击者/第三方要求解锁并查看会话列表；
 *    - 设防边界：激活假聊天模式时，UI 仅渲染预设的模拟会话，真实 Room 数据库读写、网络同步完全旁路，零真实数据暴露；
 * 2. 桌面入口死锁陷阱（Launcher Alias Deadlock）：
 *    - 攻击面：隐藏桌面图标后拨号盘广播受 OEM 定制拦截，导致永久无法唤起真应用；
 *    - 设防边界：强制检测 Dial Receiver 可达性；若环境不支持暗码唤起则拒绝隐藏桌面图标，避免不可逆死锁；
 * 3. 跨边界静默泄露（Background Leakage）：
 *    - 攻击面：后台通知、Widget 概览、系统剪贴板、多任务最近任务缩略图泄漏真实聊天片段；
 *    - 设防边界：在假聊天生效期间，全局通知强制脱敏、Widget 屏蔽真实消息摘要、窗口启用 FLAG_SECURE 保护；
 * 4. 恐慌状态自毁（Panic Cleanup）：
 *    - 伪装状态下支持紧急清除模拟缓存，且绝不误删真实账户密匙与本地 SQLCipher 数据库。
 */
object FakeChatThreatModel {
    const val THREAT_COERCION = "THREAT_COERCION_INSPECTION"
    const val THREAT_LAUNCHER_LOCKOUT = "THREAT_LAUNCHER_LOCKOUT"
    const val THREAT_DATA_LEAKAGE = "THREAT_DATA_LEAKAGE"
    const val THREAT_PANIC_WIPE = "THREAT_PANIC_WIPE"
}

object FakeChatDataBoundary {

    /**
     * 校验在当前模式下是否允许访问真实业务数据。
     * 当处于假聊天状态时，直接阻断真实 DAO/网络会话请求。
     */
    fun mayAccessRealData(isFakeModeActive: Boolean): Boolean = !isFakeModeActive

    /**
     * 确保不会在假聊天模式下泄露真实通知。
     * 若处于假模式，强制脱敏为中性系统提示。
     */
    fun sanitizeNotification(
        isFakeModeActive: Boolean,
        realTitle: String,
        realBody: String,
        mockTitle: String = "系统更新",
        mockBody: String = "您有一条新的系统服务提醒"
    ): Pair<String, String> {
        return if (isFakeModeActive) {
            mockTitle to mockBody
        } else {
            realTitle to realBody
        }
    }

    /**
     * 验证是否允许隐藏桌面图标。
     * 只有在明确具备拨号广播接收能力（或支持暗码唤起）且已设置 PIN 时才允许隐藏，
     * 防范不可逆丢失应用入口。
     */
    fun mayHideLauncherIcon(
        hasPin: Boolean,
        isSecretCodeReceiverRegistered: Boolean,
        allowDangerousHiding: Boolean = false
    ): Boolean {
        if (!hasPin) return false
        if (!isSecretCodeReceiverRegistered && !allowDangerousHiding) return false
        return true
    }

    /**
     * 假聊天模拟数据白名单校验。
     * 确保假聊天使用的所有 chatId 与 messageId 均带有特定前缀，绝不与真实数据库 ID 发生碰撞。
     */
    const val FAKE_CHAT_ID_PREFIX = "fake_mock_"

    fun isMockChatId(chatId: String): Boolean = chatId.startsWith(FAKE_CHAT_ID_PREFIX)

    fun createMockChatId(identifier: String): String = "$FAKE_CHAT_ID_PREFIX$identifier"
}
