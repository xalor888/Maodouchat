package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G183：ChatDetailViewModel 的解密相关判定（G183 收敛了 mediaDecryptFailedText
 * 与 mediaDecryptFailedTextForType 的重复 when）。
 *
 * `isDecryptable` 原本是 `ChatDetailViewModel` 的**成员**扩展，但纯做集合判定、
 * 不碰实例状态——G183 把它抽成顶层纯函数后才能被这里覆盖。
 *
 * 注：`mediaDecryptFailedText` 仍是成员扩展（内部走 `text(...)` → `getApplication`），
 * 本机无 Robolectric 测不了；它的收敛由「委托给 mediaDecryptFailedTextForType」
 * 保证只有一份 when，见 G183 台账条目。
 */
class ChatDetailDecryptPolicyTest {

    private fun msg(type: MessageType) = Message(
        id = "m1", chatId = "c1", senderId = "u1", content = "x", type = type, timestamp = 1L
    )

    @Test
    fun `decryptable types are exactly the content bearing ones`() {
        listOf(
            MessageType.TEXT, MessageType.MARKDOWN, MessageType.IMAGE, MessageType.GIF,
            MessageType.STICKER, MessageType.LOCATION, MessageType.VIDEO,
            MessageType.VOICE, MessageType.FILE,
        ).forEach { assertTrue("$it 应可解密", it.isDecryptable()) }
    }

    @Test
    fun `control types are not decryptable`() {
        listOf(
            MessageType.NUDGE, MessageType.SK_DIST, MessageType.SYSTEM, MessageType.REVOKED,
        ).forEach { assertFalse("$it 不应可解密", it.isDecryptable()) }
    }

    @Test
    fun `every enum value is classified`() {
        // 新增 MessageType 时必须显式决定它是否可解密——漏了就在这条红
        MessageType.entries.forEach { type ->
            val expected = type in setOf(
                MessageType.TEXT, MessageType.MARKDOWN, MessageType.IMAGE, MessageType.GIF,
                MessageType.STICKER, MessageType.LOCATION, MessageType.VIDEO,
                MessageType.VOICE, MessageType.FILE,
            )
            assertEquals("$type 的分类与预期不符", expected, type.isDecryptable())
        }
    }
    @Test
    fun `decryptable set has exactly nine members`() {
        assertEquals(9, MessageType.entries.count { it.isDecryptable() })
    }

    @Test
    fun `non decryptable set has exactly four members`() {
        val rest = MessageType.entries.filterNot { it.isDecryptable() }
        assertEquals(
            setOf(
                MessageType.NUDGE, MessageType.SK_DIST,
                MessageType.SYSTEM, MessageType.REVOKED,
            ),
            rest.toSet(),
        )
    }

    @Test
    fun `hidden sk dist is not decryptable`() {
        assertTrue(MessageType.SK_DIST.isHidden)
        assertFalse(MessageType.SK_DIST.isDecryptable())
    }

    @Test
    fun `classification is deterministic`() {
        val first = MessageType.entries.map { it.isDecryptable() }
        val second = MessageType.entries.map { it.isDecryptable() }
        assertEquals(first, second)
    }

    @Test
    fun `unknown wire value falls back to decryptable text`() {
        assertEquals(MessageType.TEXT, MessageType.fromWire("bogus"))
        assertEquals(MessageType.TEXT, MessageType.fromWire(null))
        assertTrue(MessageType.fromWire("bogus").isDecryptable())
    }

}
