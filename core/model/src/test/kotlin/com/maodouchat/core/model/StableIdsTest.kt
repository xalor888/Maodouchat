package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * G328c：`core/model` 此前**零测试**（审计点名的「零覆盖模块」之一）。
 * 这里补的不是「跑一遍不炸」，而是把这些 value class 的**契约**钉住：
 * 空值必须拒绝、相等性按值、以及它们是编译期的不同 ID 空间。
 */
class StableIdsTest {

    @Test
    fun `blank values are rejected`() {
        for (blank in listOf("", "   ")) {
            assertRejects { AccountId(blank) }
            assertRejects { ConversationId(blank) }
            assertRejects { MessageId(blank) }
        }
    }

    @Test
    fun `non-blank values are kept verbatim`() {
        assertEquals("u1", AccountId("u1").value)
        assertEquals("c-42", ConversationId("c-42").value)
        assertEquals("m_9", MessageId("m_9").value)
    }

    @Test
    fun `equality is by value`() {
        assertEquals(MessageId("m1"), MessageId("m1"))
        assertNotEquals(MessageId("m1"), MessageId("m2"))
    }

    private inline fun assertRejects(block: () -> Unit) {
        try {
            block()
            fail("构造应当抛 IllegalArgumentException（空值不得成为合法 ID）")
        } catch (_: IllegalArgumentException) {
            // 期望路径
        }
    }
}
