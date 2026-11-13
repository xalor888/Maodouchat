package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageTranslationDisplayTest {

    @Test
    fun preferredLanguageHit() {
        val meta = MessageMeta(
            translations = mapOf("English" to "Hello", "中文" to "你好"),
            preferredTranslationLanguage = "English",
        )
        assertEquals("Hello", meta.displayedTranslation())
    }

    @Test
    fun preferredBlankFallsBackToChinese() {
        val meta = MessageMeta(
            translations = mapOf("English" to "   ", "中文" to "你好"),
            preferredTranslationLanguage = "English",
        )
        assertEquals("你好", meta.displayedTranslation())
    }

    @Test
    fun preferredMissingFallsBackToChinese() {
        val meta = MessageMeta(
            translations = mapOf("English" to "Hello", "中文" to "你好"),
            preferredTranslationLanguage = "Français",
        )
        assertEquals("你好", meta.displayedTranslation())
    }

    @Test
    fun noPreferredUsesChinese() {
        val meta = MessageMeta(
            translations = mapOf("English" to "Hello", "中文" to "你好"),
        )
        assertEquals("你好", meta.displayedTranslation())
    }

    @Test
    fun chineseBlankFallsBackToLastNonBlank() {
        val meta = MessageMeta(
            translations = mapOf("English" to "Hello", "中文" to "  ", "日本語" to "こんにちは"),
        )
        assertEquals("こんにちは", meta.displayedTranslation())
    }

    @Test
    fun lastNonBlankSkipsTrailingBlanks() {
        val meta = MessageMeta(
            translations = mapOf("English" to "Hello", "日本語" to "  ", "Français" to "Bonjour"),
        )
        assertEquals("Bonjour", meta.displayedTranslation())
    }

    @Test
    fun allBlankReturnsNull() {
        val meta = MessageMeta(
            translations = mapOf("English" to "  ", "中文" to ""),
        )
        assertNull(meta.displayedTranslation())
    }

    @Test
    fun emptyTranslationsReturnsNull() {
        assertNull(MessageMeta().displayedTranslation())
    }
}
