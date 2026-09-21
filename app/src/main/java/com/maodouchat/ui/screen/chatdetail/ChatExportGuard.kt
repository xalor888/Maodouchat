package com.maodouchat.ui.screen.chatdetail

/**
 * 导出聊天的前置准入（G69，从 `ChatDetailViewModel.exportToUri()` 71 行里抽出）。
 *
 * **为什么抽它**：这七条准入门此前一个用例都没有，而每一条都是用户可见的故障点，
 * 且漏一条的后果差别很大：
 * - 功能关着还导出 → 绕过运营开关，导出开关形同虚设；
 * - 密聊还导出 → **隐私事故**（密聊内容落到用户自选 URI，可能被其它应用读走）；
 * - 已锁未解锁还导出 → 绕过应用锁；
 * - 空消息 / 空序列化还导出 → 写出一个空文件，用户以为导出成功。
 *
 * 本对象是纯函数：不碰 Coroutine / Room / ApiService / Android 资源 / ContentResolver。
 */
internal object ChatExportGuard {

    /** 占位 owner：未登录或测试态下 `currentUserId` 会是它，此时绝不能导出。 */
    const val PLACEHOLDER_OWNER: String = "me"

    enum class RejectReason {
        DISABLED,
        NO_SESSION,
        STALE_SESSION,
        LOCKED,
        SECRET_CHAT,
        NO_CHAT,
        EMPTY,
        SERIALIZATION_EMPTY,
    }

    sealed interface Decision {
        /** 放行，带上消息数（成功文案要用复数形式）。 */
        data class Allow(val messageCount: Int) : Decision
        data class Reject(val reason: RejectReason) : Decision
    }

    /**
     * 判定能不能导出。
     *
     * **顺序是刻意排的**：隐私相关（密聊、应用锁）排在便利性相关（空会话、空序列化）之前——
     * 同时踩中多条时，用户看到的原因必须是最重要的那条，否则「密聊被拒」会被「空消息」掩盖。
     */
    fun check(
        state: ChatDetailUiState,
        exportEnabled: Boolean,
        ownerUserId: String,
        token: String,
        sessionMayContinue: Boolean,
        serializedJson: String,
    ): Decision {
        if (!exportEnabled) return Decision.Reject(RejectReason.DISABLED)
        if (token.isBlank() || ownerUserId.isBlank() || ownerUserId == PLACEHOLDER_OWNER) {
            return Decision.Reject(RejectReason.NO_SESSION)
        }
        if (!sessionMayContinue) return Decision.Reject(RejectReason.STALE_SESSION)
        if (state.isChatLocked == true && !state.isChatUnlocked) {
            return Decision.Reject(RejectReason.LOCKED)
        }
        if (state.isSecretChat == true) return Decision.Reject(RejectReason.SECRET_CHAT)
        if (state.chat == null) return Decision.Reject(RejectReason.NO_CHAT)
        if (state.messages.isEmpty()) return Decision.Reject(RejectReason.EMPTY)
        // 空串与裸 "{}" 都算「没内容」——后者是序列化出空对象的典型表现
        val trimmed = serializedJson.trim()
        if (trimmed.isEmpty() || trimmed == "{}") {
            return Decision.Reject(RejectReason.SERIALIZATION_EMPTY)
        }
        return Decision.Allow(messageCount = state.messages.size)
    }
}
