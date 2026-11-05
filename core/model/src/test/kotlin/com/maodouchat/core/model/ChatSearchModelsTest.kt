package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatSearchModelsTest {

    @Test
    fun `mode has exactly keyword and semantic in order`() {
        assertEquals(listOf(ChatSearchMode.KEYWORD, ChatSearchMode.SEMANTIC), ChatSearchMode.entries)
    }

    @Test
    fun `scope covers all seven filters in order`() {
        assertEquals(
            listOf(
                ChatSearchScope.ALL,
                ChatSearchScope.TEXT,
                ChatSearchScope.VOICE,
                ChatSearchScope.TRANSLATION,
                ChatSearchScope.STARRED,
                ChatSearchScope.MEDIA,
                ChatSearchScope.MENTIONS,
            ),
            ChatSearchScope.entries,
        )
    }

    @Test
    fun `window covers all four ranges in order`() {
        assertEquals(
            listOf(ChatSearchWindow.ALL, ChatSearchWindow.TODAY, ChatSearchWindow.SEVEN_DAYS, ChatSearchWindow.THIRTY_DAYS),
            ChatSearchWindow.entries,
        )
    }

    @Test
    fun `valueOf round-trips every entry`() {
        for (mode in ChatSearchMode.entries) assertEquals(mode, ChatSearchMode.valueOf(mode.name))
        for (scope in ChatSearchScope.entries) assertEquals(scope, ChatSearchScope.valueOf(scope.name))
        for (window in ChatSearchWindow.entries) assertEquals(window, ChatSearchWindow.valueOf(window.name))
    }

    @Test
    fun `keyword and semantic stay distinct`() {
        assertNotEquals(ChatSearchMode.KEYWORD, ChatSearchMode.SEMANTIC)
    }
}
