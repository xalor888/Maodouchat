package com.maodouchat.core.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * G328c：`core/serialization` 此前**零测试**（零覆盖模块之一）。
 *
 * 两个 Json 实例的差别是**协议兼容策略**，不是风格：
 * - `strict`：未知字段直接失败 —— 用于自己完全掌控的持久化载荷，
 *   让「字段改名忘了迁移」在测试期就炸，而不是静默丢数据。
 * - `forwardCompatible`：忽略未知字段 —— 用于服务端可能先上线新字段的线上协议。
 * 这里把两者的差别钉住；改错方向会让线上旧客户端直接崩或静默丢字段。
 */
class MaodouJsonTest {

    @Serializable
    private data class Payload(val name: String = "n", val count: Int = 1)

    @Test
    fun `strict rejects unknown keys`() {
        try {
            MaodouJson.strict.decodeFromString(Payload.serializer(), """{"name":"a","extra":1}""")
            fail("strict 必须拒绝未知字段")
        } catch (_: Exception) {
            // 期望路径
        }
    }

    @Test
    fun `forwardCompatible ignores unknown keys`() {
        val decoded = MaodouJson.forwardCompatible.decodeFromString(
            Payload.serializer(), """{"name":"a","futureField":true}""",
        )
        assertEquals("a", decoded.name)
    }

    @Test
    fun `both instances encode defaults`() {
        // encodeDefaults=true：默认值也要写出去，否则接收端分不清「没给」与「给了默认值」
        for (json in listOf(MaodouJson.strict, MaodouJson.forwardCompatible)) {
            val text = json.encodeToString(Payload.serializer(), Payload())
            assertTrue("应包含默认值 count：$text", text.contains("count"))
            assertTrue("应包含默认值 name：$text", text.contains("name"))
        }
    }

    @Test
    fun `explicitNulls is off so absent optionals stay absent`() {
        @Serializable
        data class WithNull(val id: String, val note: String? = null)
        val text = MaodouJson.forwardCompatible.encodeToString(WithNull.serializer(), WithNull("x"))
        assertFalse("null 可选字段不该被显式写出：$text", text.contains("note"))
    }

    @Test
    fun `round trip is stable for both instances`() {
        val original = Payload(name = "群聊", count = 3)
        for (json in listOf(MaodouJson.strict, MaodouJson.forwardCompatible)) {
            val text = json.encodeToString(Payload.serializer(), original)
            assertEquals(original, json.decodeFromString(Payload.serializer(), text))
        }
    }

    @Test
    fun `forwardCompatible parses a raw json object`() {
        val obj = MaodouJson.forwardCompatible.parseToJsonElement("""{"a":1}""") as JsonObject
        assertEquals(1, obj.size)
    }
}
