package com.maodouchat.core.model

data class ChatLoadInput(
    val chat: Chat,
    /** 进入前的成员 revision；null 表示首次进入（无基线，不算「成员变更」）。 */
    val previousRevision: Long?,
    val currentUserId: String,
    /** true 表示这是 API 失败后的本地缓存回退。 */
    val fromCache: Boolean = false,
)
