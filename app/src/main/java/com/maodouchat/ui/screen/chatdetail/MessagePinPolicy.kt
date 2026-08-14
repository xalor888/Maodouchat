package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.MessageType

/**
 * 会话消息置顶权限与预览（纯逻辑，单测友好）。
 * 服务端再校验一次；客户端用于菜单显隐与本地预览文案。
 */
object MessagePinPolicy {
    const val MAX_PINS = 20

    fun canPin(
        isGroup: Boolean,
        myRole: String?,
        messageType: MessageType
    ): Boolean {
        if (!isPinnableType(messageType)) return false
        return if (isGroup) {
            val role = myRole?.uppercase().orEmpty()
            role == "OWNER" || role == "ADMIN"
        } else {
            true
        }
    }

    fun isPinnableType(type: MessageType): Boolean = when (type) {
        MessageType.TEXT,
        MessageType.MARKDOWN,
        MessageType.IMAGE,
        MessageType.GIF,
        MessageType.STICKER,
        MessageType.LOCATION,
        MessageType.VOICE,
        MessageType.VIDEO,
        MessageType.FILE -> true
        else -> false
    }

    fun wouldExceedLimit(currentCount: Int, alreadyPinned: Boolean): Boolean {
        if (alreadyPinned) return false
        return currentCount >= MAX_PINS
    }

    /** 顶栏预览：密文/未知类型用通用文案 key 由 UI 决定。 */
    fun previewKind(type: MessageType): PreviewKind = when (type) {
        MessageType.TEXT, MessageType.MARKDOWN -> PreviewKind.TEXT
        MessageType.IMAGE, MessageType.GIF -> PreviewKind.IMAGE
        MessageType.VOICE -> PreviewKind.VOICE
        MessageType.VIDEO -> PreviewKind.VIDEO
        MessageType.FILE -> PreviewKind.FILE
        MessageType.LOCATION -> PreviewKind.LOCATION
        MessageType.STICKER -> PreviewKind.STICKER
        else -> PreviewKind.GENERIC
    }

    fun textPreview(content: String, maxLen: Int = 96): String {
        // 9.153：meta 块恒在正文末尾（9.143 口径），substringBefore 取首个 <meta> 会截断
        // 含字面 <meta> 的正文；改为取最后一个 <meta> 之前的全部内容
        val plain = content.substringBeforeLast("<meta>").trim().replace('\n', ' ')
        if (plain.isBlank()) return ""
        return if (plain.length <= maxLen) plain else plain.take(maxLen - 1) + "…"
    }

    enum class PreviewKind {
        TEXT, IMAGE, VOICE, VIDEO, FILE, LOCATION, STICKER, GENERIC
    }
}
