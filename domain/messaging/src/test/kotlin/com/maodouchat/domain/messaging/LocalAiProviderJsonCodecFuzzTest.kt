package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// LocalAiProviderJsonCodec 的 fuzz：provider 列表 JSON 编解码的往返恒等与边界钉住。
// 只加测试、零生产改动；断言依据是编解码的归一化契约（空 name 取 model、baseUrl
// 去首尾空白与尾部 '/'、空 anthropicVersion / extraHeadersJson 取默认值、缺失
// 可选字段取默认值、未知 protocol 回落 OPENAI_CHAT_COMPLETIONS）。
class LocalAiProviderJsonCodecFuzzTest {

    private companion object {
        private const val ITERATIONS = 150
    }

    private fun randomToken(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomProvider(random: Random, index: Int): LocalAiProvider {
        val protocol = LocalAiProtocol.values()[random.nextInt(LocalAiProtocol.values().size)]
        return LocalAiProvider(
            id = "p$index-" + randomToken(random, random.nextInt(1, 10)),
            name = randomToken(random, random.nextInt(1, 12)),
            baseUrl = "https://host$index.example.com/v1",
            apiKey = if (random.nextBoolean()) randomToken(random, random.nextInt(0, 16)) else "",
            model = "model-$index-" + randomToken(random, random.nextInt(1, 8)),
            protocol = protocol,
            anthropicVersion = if (random.nextBoolean()) "2023-06-01" else "2024-01-01",
            organization = if (random.nextBoolean()) randomToken(random, random.nextInt(1, 8)) else "",
            extraHeadersJson = if (random.nextBoolean()) "{}" else """{"X-K":"v$index"}""",
            temperature = if (random.nextBoolean()) random.nextDouble(0.0, 2.0) else null,
            topP = if (random.nextBoolean()) random.nextDouble(0.0, 1.0) else null,
            maxTokens = random.nextInt(1, 8192),
            contextWindowTokens = random.nextInt(1000, 200_000),
            historyMessageLimit = random.nextInt(0, 100),
            timeoutSeconds = random.nextInt(1, 600),
            stream = random.nextBoolean(),
            supportsVision = random.nextBoolean()
        )
    }

    @Test
    fun `provider list round-trips on seeded random providers`() {
        val random = Random(31003)
        repeat(ITERATIONS) { i ->
            val provider = randomProvider(random, i)
            val decoded = LocalAiProviderJsonCodec.parseProviders(
                LocalAiProviderJsonCodec.encodeProviders(listOf(provider))
            )
            assertEquals(listOf(provider), decoded, "iteration $i: provider must round-trip exactly")
        }
    }

    @Test
    fun `provider list order and size round-trip`() {
        val random = Random(31004)
        repeat(50) { i ->
            val count = random.nextInt(0, 6)
            val providers = (0 until count).map { randomProvider(random, i * 10 + it) }
            val decoded = LocalAiProviderJsonCodec.parseProviders(
                LocalAiProviderJsonCodec.encodeProviders(providers)
            )
            assertEquals(providers, decoded, "iteration $i: list order and size must survive encode")
        }
    }

    @Test
    fun `parse never throws on seeded random garbage`() {
        val random = Random(31005)
        repeat(200) { i ->
            val raw = when (random.nextInt(5)) {
                0 -> randomToken(random, random.nextInt(0, 60))
                1 -> "[" + randomToken(random, random.nextInt(0, 40)) + "]"
                2 -> """{"id":"p","model":"m"}"""
                3 -> "x".repeat(random.nextInt(0, 120))
                else -> """[{"id":"","model":"","baseUrl":""}]"""
            }
            val parsed = LocalAiProviderJsonCodec.parseProviders(raw)
            assertTrue(
                parsed.all { it.id.isNotBlank() && it.model.isNotBlank() && it.baseUrl.isNotBlank() },
                "iteration $i: parsed entries must all have id, model and baseUrl"
            )
        }
        assertEquals(emptyList(), LocalAiProviderJsonCodec.parseProviders(""))
        assertEquals(emptyList(), LocalAiProviderJsonCodec.parseProviders("not json"))
        assertEquals(emptyList(), LocalAiProviderJsonCodec.parseProviders("[]"))
        assertEquals(emptyList(), LocalAiProviderJsonCodec.parseProviders("[1,2]"))
    }

    @Test
    fun `blank name falls back to model`() {
        val provider = LocalAiProvider(
            id = "p1", name = "", baseUrl = "https://x.example.com", apiKey = "", model = "m1"
        )
        val decoded = LocalAiProviderJsonCodec.parseProviders(
            LocalAiProviderJsonCodec.encodeProviders(listOf(provider))
        ).single()
        assertEquals("m1", decoded.name)
    }

    @Test
    fun `baseUrl trailing slashes and whitespace are trimmed`() {
        val raw = """[{"id":"p","name":"n","baseUrl":"  https://x.example.com/v1///  ","model":"m"}]"""
        val decoded = LocalAiProviderJsonCodec.parseProviders(raw).single()
        assertEquals("https://x.example.com/v1", decoded.baseUrl)
    }

    @Test
    fun `missing optional fields take defaults`() {
        val raw = """[{"id":"p","model":"m","baseUrl":"https://x.example.com"}]"""
        val decoded = LocalAiProviderJsonCodec.parseProviders(raw).single()
        assertEquals("m", decoded.name)
        assertEquals(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, decoded.protocol)
        assertEquals("2023-06-01", decoded.anthropicVersion)
        assertEquals("", decoded.organization)
        assertEquals("{}", decoded.extraHeadersJson)
        assertEquals(null, decoded.temperature)
        assertEquals(null, decoded.topP)
        assertEquals(4_096, decoded.maxTokens)
        assertEquals(128_000, decoded.contextWindowTokens)
        assertEquals(24, decoded.historyMessageLimit)
        assertEquals(120, decoded.timeoutSeconds)
        assertTrue(decoded.stream)
        assertEquals(false, decoded.supportsVision)
    }

    @Test
    fun `unknown protocol falls back to chat completions`() {
        val raw = """[{"id":"p","name":"n","baseUrl":"https://x.example.com","model":"m","protocol":"WHATEVER"}]"""
        val decoded = LocalAiProviderJsonCodec.parseProviders(raw).single()
        assertEquals(LocalAiProtocol.OPENAI_CHAT_COMPLETIONS, decoded.protocol)
    }

    @Test
    fun `entries with blank id model or baseUrl are skipped`() {
        val raw = "[" +
            """{"id":"","name":"n","baseUrl":"https://x.example.com","model":"m"},""" +
            """{"id":"p","name":"n","baseUrl":"","model":"m"},""" +
            """{"id":"p","name":"n","baseUrl":"https://x.example.com","model":""},""" +
            """{"id":"ok","name":"n","baseUrl":"https://x.example.com","model":"m"}]"""
        val decoded = LocalAiProviderJsonCodec.parseProviders(raw)
        assertEquals(listOf("ok"), decoded.map { it.id })
    }
}
