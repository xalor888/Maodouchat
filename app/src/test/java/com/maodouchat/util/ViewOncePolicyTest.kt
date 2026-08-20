package com.maodouchat.util

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ViewOncePolicyTest {

    private fun message(
        type: MessageType = MessageType.IMAGE,
        viewOnce: Boolean = false,
        viewOnceOpened: Boolean = false,
        senderId: String = "peer"
    ): Message = Message(
        id = "m1",
        chatId = "chat-1",
        senderId = senderId,
        content = "media",
        type = type,
        timestamp = 1_000L,
        meta = MessageMeta(viewOnce = viewOnce, viewOnceOpened = viewOnceOpened)
    )

    @Test
    fun `supports image video gif only`() {
        assertTrue(ViewOncePolicy.supports(MessageType.IMAGE))
        assertTrue(ViewOncePolicy.supports(MessageType.VIDEO))
        assertTrue(ViewOncePolicy.supports(MessageType.GIF))
        assertFalse(ViewOncePolicy.supports(MessageType.TEXT))
        assertFalse(ViewOncePolicy.supports(MessageType.VOICE))
        assertFalse(ViewOncePolicy.supports(MessageType.FILE))
    }

    @Test
    fun `isViewOnce requires flag and supported type`() {
        assertTrue(ViewOncePolicy.isViewOnce(message(type = MessageType.IMAGE, viewOnce = true)))
        assertFalse(ViewOncePolicy.isViewOnce(message(type = MessageType.IMAGE, viewOnce = false)))
        // 标记在不支持的类型上不生效
        assertFalse(ViewOncePolicy.isViewOnce(message(type = MessageType.TEXT, viewOnce = true)))
    }

    @Test
    fun `locked only for opened non-own view-once messages`() {
        val opened = message(viewOnce = true, viewOnceOpened = true)
        assertTrue(ViewOncePolicy.isLockedForViewer(opened, isOwnMessage = false))
        // 自己发的阅后即焚不锁定（发送者可回看）
        assertFalse(ViewOncePolicy.isLockedForViewer(opened, isOwnMessage = true))
        // 未打开不锁定
        assertFalse(ViewOncePolicy.isLockedForViewer(message(viewOnce = true, viewOnceOpened = false), isOwnMessage = false))
        // 非阅后即焚永不锁定
        assertFalse(ViewOncePolicy.isLockedForViewer(message(viewOnce = false, viewOnceOpened = true), isOwnMessage = false))
    }

    @Test
    fun `markOpened flips flag exactly once`() {
        val fresh = message(viewOnce = true)
        val opened = ViewOncePolicy.markOpened(fresh)
        assertTrue(ViewOncePolicy.isViewOnce(opened))
        assertTrue(opened.parsedMeta().viewOnceOpened)
        // 幂等：已打开的消息再次 markOpened 返回原对象
        assertSame(opened, ViewOncePolicy.markOpened(opened))
    }

    @Test
    fun `markOpened is no-op for non view-once messages`() {
        val normal = message(viewOnce = false)
        assertSame(normal, ViewOncePolicy.markOpened(normal))
        assertEquals(normal, ViewOncePolicy.markOpened(normal))
    }
}
