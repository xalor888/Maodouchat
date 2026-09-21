package com.maodouchat.util

import com.maodouchat.data.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G154b：`SpoilerMediaPolicy` 的测试（新建，收敛三处内联/重复的集合）。
 */
class SpoilerMediaPolicyTest {

    @Test
    fun `the three visual media types support spoiler`() {
        listOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.GIF).forEach {
            assertTrue("$it 应支持剧透", SpoilerMediaPolicy.supports(it))
        }
    }

    @Test
    fun `non visual types do not support spoiler`() {
        listOf(
            MessageType.TEXT, MessageType.MARKDOWN, MessageType.VOICE, MessageType.FILE,
            MessageType.STICKER, MessageType.LOCATION, MessageType.NUDGE,
            MessageType.SK_DIST, MessageType.SYSTEM, MessageType.REVOKED,
        ).forEach {
            assertFalse("$it 不应支持剧透", SpoilerMediaPolicy.supports(it))
        }
    }

    @Test
    fun `every enum value is classified`() {
        MessageType.entries.forEach { type ->
            val expected = type in setOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.GIF)
            assertEquals("$type 分类与预期不符", expected, SpoilerMediaPolicy.supports(type))
        }
    }

    @Test
    fun `the set has exactly three entries`() {
        assertEquals(3, SpoilerMediaPolicy.SUPPORTED.size)
    }
}
