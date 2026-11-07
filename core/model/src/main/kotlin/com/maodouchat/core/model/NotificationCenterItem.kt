package com.maodouchat.core.model

data class NotificationCenterItem(
    val id: String,
    val type: String,            // MESSAGE | MISSED_CALL | AI_TASK | POST_INTERACTION | FRIEND_REQUEST | GROUP_INVITE | REPORT | SECURITY
    val mergeKey: String,        // 用于合并同类同主键的通知
    val title: String,
    val subtitle: String? = null,
    val preview: String? = null,
    val deeplink: String? = null,
    val extra: Map<String, String> = emptyMap(),
    val read: Boolean = false,
    val count: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
