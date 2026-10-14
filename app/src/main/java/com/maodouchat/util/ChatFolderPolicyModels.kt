package com.maodouchat.util

/**
 * 会话文件夹（本地 + 云端同步元数据；chatIds 为会话 id，非消息内容）。
 * 纯函数，可单测。
 */
data class ChatFolder(
    val id: String,
    val name: String,
    val chatIds: List<String> = emptyList(),
    val sortOrder: Int = 0,
)
