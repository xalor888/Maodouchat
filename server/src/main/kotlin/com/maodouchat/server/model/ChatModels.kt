package com.maodouchat.server.model

import kotlinx.serialization.Serializable

// 聊天 DTO：会话文件夹、客户端偏好、已读回执。


@Serializable
data class MarkReadResponse(val status: String, val updated: Int)


@Serializable
data class MarkReadRequest(val throughId: String? = null)


@Serializable
data class ChatFolderDto(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val chatIds: List<String> = emptyList(),
    val updatedAt: Long = 0
)


@Serializable
data class ChatFoldersSyncRequest(val folders: List<ChatFolderDto> = emptyList())


@Serializable
data class ChatFoldersSyncResponse(
    val folders: List<ChatFolderDto> = emptyList(),
    val updatedAt: Long = 0
)


/** 多端外观/语言/列表/AI 写作风格/应用锁超时/防截屏偏好（非密钥、非会话正文） */
@Serializable
data class ClientPrefsDto(
    val themeMode: String = "system",
    val themeStyle: String = "maodou",
    val accentColor: String = "none",
    val languageMode: String = "system",
    val chatWallpaper: String = "default",
    val chatFontScale: String = "normal",
    val linkPreviewEnabled: Boolean = true,
    val unreadPriorityEnabled: Boolean = true,
    val writingStyleEnabled: Boolean = false,
    val writingStylePreset: String = "none",
    val writingStyleCustom: String = "",
    val appLockTimeoutMinutes: Long = 5,
    val screenSecureEnabled: Boolean = false,
    val sensitiveGateEnabled: Boolean = true,
    val updatedAt: Long = 0
)


@Serializable
data class ClientPrefsUpdateRequest(
    val themeMode: String? = null,
    val themeStyle: String? = null,
    val accentColor: String? = null,
    val languageMode: String? = null,
    val chatWallpaper: String? = null,
    val chatFontScale: String? = null,
    val linkPreviewEnabled: Boolean? = null,
    val unreadPriorityEnabled: Boolean? = null,
    val writingStyleEnabled: Boolean? = null,
    val writingStylePreset: String? = null,
    val writingStyleCustom: String? = null,
    val appLockTimeoutMinutes: Long? = null,
    val screenSecureEnabled: Boolean? = null,
    val sensitiveGateEnabled: Boolean? = null
)


@Serializable
data class ReadReceiptResponse(val userId: String, val readAt: Long)
