package com.maodouchat.domain.conversation

import com.maodouchat.core.model.QuietWindow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ChatQuietHoursJsonPolicy 的 fuzz：免打扰时段 JSON 编解码的往返恒等与边界钉住。
// 只加测试、零生产改动；断言依据是编解码的归一化契约（start/end 钳制到 0..1439、
// silentUntil ≤0 时记 0 且不编码、损坏 JSON 回空表）。
class ChatQuietHoursJsonPolicyFuzzTest {

    private companion object {
        private const val ITERATIONS = 150
    }

    private fun randomToken(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomEntries(random: Random, seed: Int): Map<String, ChatQuietHoursEntry> {
        val count = random.nextInt(0, 6)
        return (0 until count).associate { i ->
            val chatId = "chat${seed}_$i-" + randomToken(random, random.nextInt(1, 8))
            val window = QuietWindow(
                enabled = random.nextBoolean(),
                startMinute = random.nextInt(0, 1440),
                endMinute = random.nextInt(0, 1440)
            )
            val silentUntil = if (random.nextBoolean()) 0L else random.nextLong(1, 4_000_000_000_000L)
            chatId to ChatQuietHoursEntry(window, silentUntil)
        }
    }

    @Test
    fun `entries round-trip on seeded random maps`() {
        val random = Random(31008)
        repeat(ITERATIONS) { i ->
            val entries = randomEntries(random, i)
            val decoded = ChatQuietHoursJsonPolicy.decodeEntries(ChatQuietHoursJsonPolicy.encodeEntries(entries))
            assertEquals(entries, decoded, "iteration $i: entries must round-trip exactly")
        }
    }

    @Test
    fun `decode never throws on seeded random garbage`() {
        val random = Random(31009)
        repeat(200) { i ->
            val raw = when (random.nextInt(4)) {
                0 -> randomToken(random, random.nextInt(0, 60))
                1 -> "{" + randomToken(random, random.nextInt(0, 40)) + "}"
                2 -> "x".repeat(random.nextInt(0, 120))
                else -> """{"c1":{"enabled":true,"start":60,"end":120}}"""
            }
            val decoded = ChatQuietHoursJsonPolicy.decodeEntries(raw)
            assertTrue(
                decoded.values.all {
                    it.window.startMinute in 0..1439 && it.window.endMinute in 0..1439 && it.silentUntilMs >= 0L
                },
                "iteration $i: decoded windows must be clamped and silentUntil non-negative"
            )
        }
        assertEquals(emptyMap(), ChatQuietHoursJsonPolicy.decodeEntries(null))
        assertEquals(emptyMap(), ChatQuietHoursJsonPolicy.decodeEntries(""))
        assertEquals(emptyMap(), ChatQuietHoursJsonPolicy.decodeEntries("   "))
        assertEquals(emptyMap(), ChatQuietHoursJsonPolicy.decodeEntries("[]"))
        assertEquals(emptyMap(), ChatQuietHoursJsonPolicy.decodeEntries("not json"))
    }

    @Test
    fun `out-of-range minutes are clamped`() {
        val raw = """{"c1":{"enabled":true,"start":-5,"end":2000}}"""
        val decoded = ChatQuietHoursJsonPolicy.decodeEntries(raw)
        assertEquals(QuietWindow(enabled = true, startMinute = 0, endMinute = 1439), decoded.getValue("c1").window)
    }

    @Test
    fun `non-positive silentUntil becomes zero`() {
        val raw = """{"c1":{"enabled":false,"start":0,"end":0,"silent_until":-100}}"""
        assertEquals(0L, ChatQuietHoursJsonPolicy.decodeEntries(raw).getValue("c1").silentUntilMs)
        val encoded = ChatQuietHoursJsonPolicy.encodeEntries(
            mapOf("c1" to ChatQuietHoursEntry(QuietWindow.OFF, 0L))
        )
        // silentUntil 为 0 时不编码，解回仍是同一条目（OFF 窗口、silentUntil 0）。
        assertEquals(
            mapOf("c1" to ChatQuietHoursEntry(QuietWindow.OFF, 0L)),
            ChatQuietHoursJsonPolicy.decodeEntries(encoded)
        )
    }

    @Test
    fun `non-object entries are skipped`() {
        val raw = """{"c1":"nope","c2":{"enabled":true,"start":60,"end":120}}"""
        val decoded = ChatQuietHoursJsonPolicy.decodeEntries(raw)
        assertEquals(listOf("c2"), decoded.keys.toList())
        assertEquals(QuietWindow(enabled = true, startMinute = 60, endMinute = 120), decoded.getValue("c2").window)
    }

    @Test
    fun `with helpers keep silentUntil and window independent`() {
        val entries = mapOf("c1" to ChatQuietHoursEntry(QuietWindow(enabled = true, startMinute = 60, endMinute = 120), 9_999L))
        val widened = ChatQuietHoursJsonPolicy.withQuietWindow(entries, "c1", QuietWindow(enabled = false, startMinute = 0, endMinute = 0))
        assertEquals(9_999L, widened.getValue("c1").silentUntilMs)
        val silenced = ChatQuietHoursJsonPolicy.withSilentUntil(entries, "c1", 0L)
        assertEquals(QuietWindow(enabled = true, startMinute = 60, endMinute = 120), silenced.getValue("c1").window)
        assertEquals(0L, silenced.getValue("c1").silentUntilMs)
        val off = mapOf("c1" to ChatQuietHoursEntry(QuietWindow.OFF, 0L))
        assertEquals(emptyMap(), ChatQuietHoursJsonPolicy.withSilentUntil(off, "c1", 0L))
        assertEquals(entries, ChatQuietHoursJsonPolicy.withoutChat(entries, "nope"))
    }
}
