package com.maodouchat.core.session

/** 冻结会话上下文：认证会话的最小稳定快照，供领域层只读注入。 */
data class SessionContext(
    val userId: String,
    val deviceId: Int,
) {
    companion object {
        val EMPTY = SessionContext(userId = "", deviceId = 0)
    }
}
