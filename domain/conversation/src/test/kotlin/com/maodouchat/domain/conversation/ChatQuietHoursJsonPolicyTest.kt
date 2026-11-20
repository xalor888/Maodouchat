package com.maodouchat.domain.conversation

import com.maodouchat.core.model.QuietWindow
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatQuietHoursJsonPolicyTest {

    private val window = QuietWindow(enabled = true, startMinute = 60, endMinute = 120)

    @Test
    fun decodeNullReturnsEmpty() {
        assertEquals(emptyMap<String, ChatQuietHoursEntry>(), ChatQuietHoursJsonPolicy.decodeEntries(null))
    }

    @Test
    fun decodeBlankReturnsEmpty() {
        assertEquals(emptyMap<String, ChatQuietHoursEntry>(), ChatQuietHoursJsonPolicy.decodeEntries(""))
        assertEquals(emptyMap<String, ChatQuietHoursEntry>(), ChatQuietHoursJsonPolicy.decodeEntries("   "))
    }

    @Test
    fun decodeCorruptJsonReturnsEmpty() {
        assertEquals(emptyMap<String, ChatQuietHoursEntry>(), ChatQuietHoursJsonPolicy.decodeEntries("not json"))
        assertEquals(emptyMap<String, ChatQuietHoursEntry>(), ChatQuietHoursJsonPolicy.decodeEntries("{broken"))
    }

    @Test
    fun decodeSkipsNonObjectEntry() {
        val raw = "{\"c1\":\"not-an-object\",\"c2\":{\"enabled\":true,\"start\":60,\"end\":120}}"
        assertEquals(
            mapOf("c2" to ChatQuietHoursEntry(window)),
            ChatQuietHoursJsonPolicy.decodeEntries(raw)
        )
    }

    @Test
    fun roundTripPreservesEntries() {
        val entries = mapOf(
            "c1" to ChatQuietHoursEntry(window, 1700000000000L),
            "c2" to ChatQuietHoursEntry(QuietWindow.OFF)
        )
        assertEquals(entries, ChatQuietHoursJsonPolicy.decodeEntries(ChatQuietHoursJsonPolicy.encodeEntries(entries)))
    }

    @Test
    fun decodeMissingFieldsDefaultToOff() {
        assertEquals(
            mapOf("c1" to ChatQuietHoursEntry(QuietWindow.OFF)),
            ChatQuietHoursJsonPolicy.decodeEntries("{\"c1\":{}}")
        )
    }

    @Test
    fun decodeCoercesStartEndIntoDayRange() {
        val raw = "{\"c1\":{\"enabled\":true,\"start\":2000,\"end\":-5}}"
        assertEquals(
            mapOf("c1" to ChatQuietHoursEntry(QuietWindow(true, 1439, 0))),
            ChatQuietHoursJsonPolicy.decodeEntries(raw)
        )
    }

    @Test
    fun decodeNormalizesNonPositiveSilentUntilToZero() {
        val raw = "{\"c1\":{\"enabled\":true,\"start\":60,\"end\":120,\"silent_until\":-5}," +
            "\"c2\":{\"enabled\":true,\"start\":60,\"end\":120,\"silent_until\":0}}"
        assertEquals(
            mapOf(
                "c1" to ChatQuietHoursEntry(window),
                "c2" to ChatQuietHoursEntry(window)
            ),
            ChatQuietHoursJsonPolicy.decodeEntries(raw)
        )
    }

    @Test
    fun encodeOmitsSilentUntilWhenZeroAndKeepsItWhenPositive() {
        val encoded = ChatQuietHoursJsonPolicy.encodeEntries(
            mapOf("c1" to ChatQuietHoursEntry(window), "c2" to ChatQuietHoursEntry(window, 1700000000000L))
        )
        val decoded = ChatQuietHoursJsonPolicy.decodeEntries(encoded)
        assertEquals(
            mapOf("c1" to ChatQuietHoursEntry(window), "c2" to ChatQuietHoursEntry(window, 1700000000000L)),
            decoded
        )
    }

    @Test
    fun withQuietWindowKeepsExistingSilentUntil() {
        val entries = mapOf("c1" to ChatQuietHoursEntry(QuietWindow.OFF, 1700000000000L))
        val updated = ChatQuietHoursJsonPolicy.withQuietWindow(entries, "c1", window)
        assertEquals(mapOf("c1" to ChatQuietHoursEntry(window, 1700000000000L)), updated)
        assertEquals(entries, mapOf("c1" to ChatQuietHoursEntry(QuietWindow.OFF, 1700000000000L)))
    }

    @Test
    fun withQuietWindowBlankChatIdReturnsEntriesUnchanged() {
        val entries = mapOf("c1" to ChatQuietHoursEntry(window))
        assertEquals(entries, ChatQuietHoursJsonPolicy.withQuietWindow(entries, "   ", QuietWindow.OFF))
    }

    @Test
    fun withSilentUntilSetsAndClears() {
        val entries = mapOf("c1" to ChatQuietHoursEntry(window))
        val set = ChatQuietHoursJsonPolicy.withSilentUntil(entries, "c1", 1700000000000L)
        assertEquals(mapOf("c1" to ChatQuietHoursEntry(window, 1700000000000L)), set)
        val cleared = ChatQuietHoursJsonPolicy.withSilentUntil(set, "c1", 0L)
        assertEquals(mapOf("c1" to ChatQuietHoursEntry(window)), cleared)
    }

    @Test
    fun withSilentUntilCreatesEntryForUnknownChat() {
        val created = ChatQuietHoursJsonPolicy.withSilentUntil(emptyMap(), "c9", 1700000000000L)
        assertEquals(mapOf("c9" to ChatQuietHoursEntry(QuietWindow.OFF, 1700000000000L)), created)
    }

    @Test
    fun withSilentUntilRemovesEmptyOffEntry() {
        val entries = mapOf("c1" to ChatQuietHoursEntry(QuietWindow.OFF, 1700000000000L))
        val cleared = ChatQuietHoursJsonPolicy.withSilentUntil(entries, "c1", 0L)
        assertEquals(emptyMap<String, ChatQuietHoursEntry>(), cleared)
    }

    @Test
    fun withSilentUntilBlankChatIdReturnsEntriesUnchanged() {
        val entries = mapOf("c1" to ChatQuietHoursEntry(window))
        assertEquals(entries, ChatQuietHoursJsonPolicy.withSilentUntil(entries, "", 1700000000000L))
    }

    @Test
    fun withoutChatRemovesOnlyExisting() {
        val entries = mapOf("c1" to ChatQuietHoursEntry(window), "c2" to ChatQuietHoursEntry(QuietWindow.OFF))
        assertEquals(mapOf("c2" to ChatQuietHoursEntry(QuietWindow.OFF)), ChatQuietHoursJsonPolicy.withoutChat(entries, "c1"))
        assertEquals(entries, ChatQuietHoursJsonPolicy.withoutChat(entries, "c9"))
    }

    @Test
    fun accessorsReturnOffAndZeroForMissingOrBlankChat() {
        val entries = mapOf("c1" to ChatQuietHoursEntry(window, 1700000000000L))
        assertEquals(window, ChatQuietHoursJsonPolicy.quietWindow(entries, "c1"))
        assertEquals(1700000000000L, ChatQuietHoursJsonPolicy.silentUntil(entries, "c1"))
        assertEquals(QuietWindow.OFF, ChatQuietHoursJsonPolicy.quietWindow(entries, "c9"))
        assertEquals(0L, ChatQuietHoursJsonPolicy.silentUntil(entries, "c9"))
        assertEquals(QuietWindow.OFF, ChatQuietHoursJsonPolicy.quietWindow(entries, ""))
        assertEquals(0L, ChatQuietHoursJsonPolicy.silentUntil(entries, ""))
    }
}
