package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G328c：`IdGenerator` 是「领域层不直接 `UUID.randomUUID()`」的冻结接口，
 * 存在的意义就是可注入。这里同时钉住默认实现的格式与唯一性——
 * 格式变了会让服务端的 id 校验正则失配，而这是**静默**的。
 */
class UuidIdGeneratorTest {

    @Test
    fun `nextId keeps the prefix and appends 16 hex chars`() {
        val id = UuidIdGenerator.nextId("msg")
        assertTrue("应带前缀：$id", id.startsWith("msg_"))
        val suffix = id.removePrefix("msg_")
        assertEquals("后缀应为 16 个字符：$id", 16, suffix.length)
        assertTrue("后缀应只含 0-9a-f：$id", suffix.all { it in "0123456789abcdef" })
    }

    @Test
    fun `nextId does not collide across many calls`() {
        val ids = (1..2_000).map { UuidIdGenerator.nextId("x") }.toSet()
        assertEquals("2000 次调用不应出现重复（UUID 截断 16 位 hex = 64 bit）", 2_000, ids.size)
    }

    @Test
    fun `nextId honours different prefixes independently`() {
        assertTrue(UuidIdGenerator.nextId("a").startsWith("a_"))
        assertTrue(UuidIdGenerator.nextId("").startsWith("_"))
    }

    @Test
    fun `a fake IdGenerator can be injected for determinism`() {
        // 这正是这个接口存在的理由：领域层测试不该依赖随机数。
        val fake = IdGenerator { prefix -> "${prefix}_fixed" }
        assertEquals("m_fixed", fake.nextId("m"))
        assertEquals("m_fixed", fake.nextId("m"))
    }
}
