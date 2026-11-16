package com.maodouchat.domain.conversation

import com.maodouchat.core.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListItemPreviewPolicyTest {

    private fun preview(
        isLocked: Boolean = false,
        secretBlocked: Boolean = false,
        type: MessageType = MessageType.TEXT,
        lastMessage: String = "你好",
        mediaLabel: String? = null,
    ) = ChatListItemPreviewPolicy.resolveMessagePreview(
        isLocked = isLocked,
        lockedPreview = "已锁定",
        secretBlocked = secretBlocked,
        secretPreview = "密聊消息",
        lastMessageType = type,
        lastMessage = lastMessage,
        nudgeLabel = "戳了你一下",
        mediaLabel = mediaLabel,
        encryptedPlaceholder = "加密消息",
    )

    @Test
    fun `locked preview wins over everything`() {
        assertEquals("已锁定", preview(isLocked = true, type = MessageType.NUDGE, mediaLabel = "图片"))
    }

    @Test
    fun `secret blocked preview wins over message`() {
        assertEquals("密聊消息", preview(secretBlocked = true, mediaLabel = "图片"))
    }

    @Test
    fun `nudge shows nudge label`() {
        assertEquals("戳了你一下", preview(type = MessageType.NUDGE, mediaLabel = "图片"))
    }

    @Test
    fun `media label shown when present`() {
        assertEquals("图片", preview(type = MessageType.IMAGE, mediaLabel = "图片"))
    }

    @Test
    fun `plain text shown as-is`() {
        assertEquals("你好", preview())
    }

    @Test
    fun `empty message falls back to encrypted placeholder`() {
        assertEquals("加密消息", preview(lastMessage = ""))
    }

    @Test
    fun `contact card marker stripped from preview`() {
        assertEquals("你好", preview(lastMessage = "你好\n[contactUser:u1]"))
    }

    @Test
    fun `scheduled label outranks typing and draft`() {
        assertEquals(
            "定时消息",
            ChatListItemPreviewPolicy.resolveFinalPreview("对方正在输入…", "草稿：你好", "你好", "定时消息"),
        )
    }

    @Test
    fun `typing outranks draft and message`() {
        assertEquals(
            "对方正在输入…",
            ChatListItemPreviewPolicy.resolveFinalPreview("对方正在输入…", "草稿：你好", "你好", null),
        )
    }

    @Test
    fun `draft outranks message, message is the fallback`() {
        assertEquals(
            "草稿：你好",
            ChatListItemPreviewPolicy.resolveFinalPreview(null, "草稿：你好", "你好", null),
        )
        assertEquals(
            "你好",
            ChatListItemPreviewPolicy.resolveFinalPreview(null, null, "你好", null),
        )
    }
}
