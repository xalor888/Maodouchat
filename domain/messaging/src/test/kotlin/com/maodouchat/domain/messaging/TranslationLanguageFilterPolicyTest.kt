package com.maodouchat.domain.messaging

import com.maodouchat.core.model.TranslationLanguageOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranslationLanguageFilterPolicyTest {

    private fun option(wire: String) = TranslationLanguageOption(wireValue = wire, labelResource = 0)

    private val options = listOf(
        option("en") to "English",
        option("zh") to "Chinese",
        option("ja") to "Japanese",
    )

    @Test
    fun `matches by label case-insensitively`() {
        assertTrue(TranslationLanguageFilterPolicy.matches(options[0].first, "english", "ENG"))
    }

    @Test
    fun `matches by wire value`() {
        assertTrue(TranslationLanguageFilterPolicy.matches(options[1].first, "Chinese", "zh"))
    }

    @Test
    fun `no match returns false`() {
        assertFalse(TranslationLanguageFilterPolicy.matches(options[0].first, "English", "fr"))
    }

    @Test
    fun `filter blank returns all`() {
        assertEquals(options, TranslationLanguageFilterPolicy.filter(options, "  "))
    }

    @Test
    fun `filter trims query`() {
        assertEquals(
            listOf(options[2]),
            TranslationLanguageFilterPolicy.filter(options, "  JAPANESE "),
        )
    }

    @Test
    fun `filter no match returns empty`() {
        assertEquals(
            emptyList<Pair<TranslationLanguageOption, String>>(),
            TranslationLanguageFilterPolicy.filter(options, "zzz"),
        )
    }
}
