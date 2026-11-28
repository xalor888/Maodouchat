package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// draftDefaults restated by hand: one fixed triple per protocol.
internal fun draftDefaultsOracle168(protocol: Int): Triple<String, String, String> = when (protocol) {
    0 -> Triple("OpenAI Chat Completions", "https://api.openai.com/v1", "gpt-4o-mini")
    1 -> Triple("OpenAI Responses", "https://api.openai.com/v1", "gpt-4.1-mini")
    else -> Triple("Anthropic", "https://api.anthropic.com", "claude-sonnet-4-5")
}

// Production draftDefaults returns the triple directly.
internal fun draftDefaultsProduction168(protocol: Int): Triple<String, String, String> =
    LocalAiProviderDraftPolicy.draftDefaults(LocalAiProtocol.entries[protocol])

// ---------- generators ----------

internal data class DraftCase168(val protocol: Int)

internal fun randomDraftCase168(rnd: Random): DraftCase168 =
    DraftCase168(protocol = rnd.nextInt(3))

internal fun draftWireJson168(c: DraftCase168): String =
    "{\"protocol\":${c.protocol}}"

class LocalAiProviderDraftPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // The three protocols, each with its hand-written triple.
        val expected = listOf(
            Triple("OpenAI Chat Completions", "https://api.openai.com/v1", "gpt-4o-mini"),
            Triple("OpenAI Responses", "https://api.openai.com/v1", "gpt-4.1-mini"),
            Triple("Anthropic", "https://api.anthropic.com", "claude-sonnet-4-5")
        )
        for (protocol in 0 until 3) {
            assertEquals(
                expected[protocol],
                draftDefaultsProduction168(protocol),
                "draftDefaults(protocol=$protocol)"
            )
            assertEquals(
                expected[protocol],
                draftDefaultsOracle168(protocol),
                "oracle(protocol=$protocol)"
            )
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(168002)
        repeat(4000) {
            val c = randomDraftCase168(rnd)
            assertEquals(
                draftDefaultsOracle168(c.protocol),
                draftDefaultsProduction168(c.protocol),
                "draftDefaults(random)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every protocol, twice, so a stateful slip would show.
        for (protocol in 0 until 3) {
            repeat(2) {
                assertEquals(
                    draftDefaultsOracle168(protocol),
                    draftDefaultsProduction168(protocol),
                    "sweep-draft(protocol=$protocol)"
                )
            }
        }
    }
}
