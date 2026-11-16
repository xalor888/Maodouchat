package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiProviderFormPolicyTest {

    @Test
    fun `text fields clip at their limits`() {
        assertEquals(80, AiProviderFormPolicy.clipName("a".repeat(100)).length)
        assertEquals(240, AiProviderFormPolicy.clipBaseUrl("b".repeat(300)).length)
        assertEquals(80, AiProviderFormPolicy.clipModel("m".repeat(90)).length)
        assertEquals(200, AiProviderFormPolicy.clipApiKey("k".repeat(250)).length)
        assertEquals(80, AiProviderFormPolicy.clipOrganization("o".repeat(90)).length)
        assertEquals(40, AiProviderFormPolicy.clipAnthropicVersion("v".repeat(50)).length)
        assertEquals(1_000, AiProviderFormPolicy.clipExtraHeaders("h".repeat(1_200)).length)
        assertEquals("abc", AiProviderFormPolicy.clipName("abc"))
    }

    @Test
    fun `digit fields keep only digits and clip`() {
        assertEquals("12345678", AiProviderFormPolicy.clipContextWindow("12a34b567890"))
        assertEquals("123", AiProviderFormPolicy.clipHistoryLimit("1a2b3c4"))
        assertEquals("123456", AiProviderFormPolicy.clipMaxTokens("1234567"))
        assertEquals("123", AiProviderFormPolicy.clipTimeout("12x3"))
    }

    @Test
    fun `temperature and topP clip to 8 chars`() {
        assertEquals("12345678", AiProviderFormPolicy.clipTemperature("123456789"))
        assertEquals("0.123456", AiProviderFormPolicy.clipTopP("0.123456789"))
    }

    @Test
    fun `base url trims and drops trailing slashes`() {
        assertEquals("https://x", AiProviderFormPolicy.normalizeBaseUrl("  https://x///  "))
        assertEquals("", AiProviderFormPolicy.normalizeBaseUrl(""))
        assertEquals("https://x/y", AiProviderFormPolicy.normalizeBaseUrl("https://x/y"))
    }

    @Test
    fun `name falls back when blank`() {
        assertEquals("old", AiProviderFormPolicy.normalizeName("", "old"))
        assertEquals("old", AiProviderFormPolicy.normalizeName("  ", "old"))
        assertEquals("new", AiProviderFormPolicy.normalizeName("new", "old"))
    }

    @Test
    fun `anthropic version and extra headers default when blank`() {
        assertEquals("2023-06-01", AiProviderFormPolicy.normalizeAnthropicVersion("  "))
        assertEquals("2024-01-01", AiProviderFormPolicy.normalizeAnthropicVersion("2024-01-01"))
        assertEquals("{}", AiProviderFormPolicy.normalizeExtraHeaders(""))
        assertEquals("{}", AiProviderFormPolicy.normalizeExtraHeaders(" {} "))
    }

    @Test
    fun `numeric fields parse with defaults`() {
        assertEquals(4_096, AiProviderFormPolicy.parseMaxTokens(""))
        assertEquals(512, AiProviderFormPolicy.parseMaxTokens("512"))
        assertEquals(128_000, AiProviderFormPolicy.parseContextWindow(""))
        assertEquals(24, AiProviderFormPolicy.parseHistoryLimit(""))
        assertEquals(120, AiProviderFormPolicy.parseTimeout(""))
        assertEquals(1.5, AiProviderFormPolicy.parseTemperature(" 1.5 "))
        assertNull(AiProviderFormPolicy.parseTemperature("abc"))
        assertNull(AiProviderFormPolicy.parseTopP(""))
    }
}
