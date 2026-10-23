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

    @Test
    fun `gif is spoiler eligible but sticker is not`() {
        // 都是动图：GIF 支持剧透是产品决策，贴纸刻意排除——别把 STICKER 当 GIF 顺手收进去
        assertTrue(SpoilerMediaPolicy.supports(MessageType.GIF))
        assertFalse(SpoilerMediaPolicy.supports(MessageType.STICKER))
    }

    @Test
    fun `supported types match viewonce today but stay an independent decision`() {
        // 回归：取值与 ViewOncePolicy 一致是巧合，注释里已声明概念独立——将来新增类型
        // 可能只支持其一；这个测试钉住的是「当前取值」，不是「必须永远一致」
        MessageType.entries.forEach {
            assertEquals("$it 剧透与阅后即焚取值应一致", ViewOncePolicy.supports(it), SpoilerMediaPolicy.supports(it))
        }
        assertEquals(
            setOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.GIF),
            SpoilerMediaPolicy.SUPPORTED
        )
    }

    @Test
    fun `supports is just membership in the public set`() {
        // supports 的语义就是「在 SUPPORTED 里」——任何枚举值都不得有例外分支
        MessageType.entries.forEach {
            assertEquals("$it", it in SpoilerMediaPolicy.SUPPORTED, SpoilerMediaPolicy.supports(it))
        }
    }

    @Test
    fun `the supported set cannot be mutated from outside`() {
        // 重复/并发安全：集合是 setOf 产物，外部拿不到可变引用
        kotlin.test.assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (SpoilerMediaPolicy.SUPPORTED as MutableSet<MessageType>).add(MessageType.TEXT)
        }
        repeat(3) {
            assertTrue(SpoilerMediaPolicy.supports(MessageType.IMAGE))
        }
    }
}
