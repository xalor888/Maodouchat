package com.maodouchat.data.repository

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G153：`MessagePersistencePolicy.preserveLocalMediaFlags` 的测试。
 *
 * 它守护四个「本地才有意义」的旗标：合并消息时，任一侧为真就不能被另一侧抹掉。
 * 否则「阅后即焚已焚」会在一次普通同步后被重置，用户能再看一次。
 */
class PreserveLocalMediaFlagsTest {

    private fun msg(
        viewOnce: Boolean = false,
        viewOnceOpened: Boolean = false,
        spoilerMedia: Boolean = false,
        spoilerRevealed: Boolean = false,
    ) = Message(
        id = "m1",
        chatId = "c1",
        senderId = "alice",
        content = "body",
        type = MessageType.TEXT,
        timestamp = 1L,
        // 四个旗标是 MessageMeta 的字段；content 里没有 <meta> 块时 parsedMeta() 就返回它
        meta = MessageMeta(
            viewOnce = viewOnce,
            viewOnceOpened = viewOnceOpened,
            spoilerMedia = spoilerMedia,
            spoilerRevealed = spoilerRevealed,
        ),
    )

    private fun metaOf(m: Message) = m.parsedMeta()

    @Test
    fun `all flags false leaves the message untouched`() {
        val existing = msg()
        val incoming = msg()
        val merged = msg()
        // 三边全假 -> 不应重编码，直接返回同一实例
        assertSame(merged, preserveLocalMediaFlags(existing, incoming, merged))
    }

    @Test
    fun `every flag survives from the existing side`() {
        val existing = msg(viewOnce = true, viewOnceOpened = true, spoilerMedia = true, spoilerRevealed = true)
        val merged = msg()
        val out = preserveLocalMediaFlags(existing, msg(), merged)
        val meta = metaOf(out)
        assertTrue(meta.viewOnce)
        assertTrue(meta.viewOnceOpened)
        assertTrue(meta.spoilerMedia)
        assertTrue(meta.spoilerRevealed)
    }

    @Test
    fun `every flag survives from the incoming side`() {
        val incoming = msg(viewOnce = true, viewOnceOpened = true, spoilerMedia = true, spoilerRevealed = true)
        val merged = msg()
        val out = preserveLocalMediaFlags(msg(), incoming, merged)
        val meta = metaOf(out)
        assertTrue(meta.viewOnce)
        assertTrue(meta.viewOnceOpened)
        assertTrue(meta.spoilerMedia)
        assertTrue(meta.spoilerRevealed)
    }

    @Test
    fun `every flag survives from the merged side`() {
        val merged = msg(viewOnce = true, viewOnceOpened = true, spoilerMedia = true, spoilerRevealed = true)
        val out = preserveLocalMediaFlags(msg(), msg(), merged)
        val meta = metaOf(out)
        assertTrue(meta.viewOnce)
        assertTrue(meta.viewOnceOpened)
        assertTrue(meta.spoilerMedia)
        assertTrue(meta.spoilerRevealed)
    }

    @Test
    fun `each flag is independent`() {
        // 只有 viewOnce 为真时，其余三个必须保持假
        val out = preserveLocalMediaFlags(msg(viewOnce = true), msg(), msg())
        val meta = metaOf(out)
        assertTrue(meta.viewOnce)
        assertFalse(meta.viewOnceOpened)
        assertFalse(meta.spoilerMedia)
        assertFalse(meta.spoilerRevealed)
    }

    @Test
    fun `merged already carrying every flag is returned as is`() {
        val merged = msg(viewOnce = true, viewOnceOpened = true, spoilerMedia = true, spoilerRevealed = true)
        // protectedMeta == mergedMeta -> 原样返回，不重新编码
        assertSame(merged, preserveLocalMediaFlags(msg(), msg(), merged))
    }

    @Test
    fun `other meta fields are carried through unchanged`() {
        // 合并结果里的非旗标字段不能被 preserve 逻辑抹掉
        val merged = Message(
            id = "m1", chatId = "c1", senderId = "alice", content = "body",
            type = MessageType.TEXT, timestamp = 1L,
            meta = MessageMeta(fileName = "a.png", replyToId = "r1"),
        )
        val out = preserveLocalMediaFlags(msg(viewOnce = true), msg(), merged)
        val meta = metaOf(out)
        assertEquals("a.png", meta.fileName)
        assertEquals("r1", meta.replyToId)
        assertTrue(meta.viewOnce)
    }
}
