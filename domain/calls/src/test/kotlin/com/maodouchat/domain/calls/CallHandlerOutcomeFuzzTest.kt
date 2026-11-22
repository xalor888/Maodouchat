package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CallHandlerOutcomeFuzzTest {

    private val random = Random(0xCA11_2026L)

    private fun randomString(): String {
        val pool = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf(' ', '_', '-', '.', 'é', '中', '文')
        return (0 until random.nextInt(0, 12)).map { pool.random(random) }.joinToString("")
    }

    private fun randomOutcome(): Outcome =
        when (random.nextInt(4)) {
            0 -> Outcome.SessionExpired
            1 -> Outcome.Dropped
            2 -> Outcome.OpenChat(randomString())
            else -> Outcome.Failed(if (random.nextBoolean()) null else randomString())
        }

    private fun label(outcome: Outcome): String =
        when (outcome) {
            Outcome.SessionExpired -> "expired"
            Outcome.Dropped -> "dropped"
            is Outcome.OpenChat -> "open:${outcome.chatId}"
            is Outcome.Failed -> "failed:${outcome.message}"
        }

    @Test
    fun `outcome equality follows its payload`() {
        repeat(200) {
            when (val outcome = randomOutcome()) {
                Outcome.SessionExpired -> assertEquals(Outcome.SessionExpired, outcome)
                Outcome.Dropped -> assertEquals(Outcome.Dropped, outcome)
                is Outcome.OpenChat -> {
                    assertEquals(outcome, outcome.copy())
                    assertEquals(outcome.hashCode(), outcome.copy().hashCode())
                    assertNotEquals(outcome, outcome.copy(chatId = outcome.chatId + "x"))
                }
                is Outcome.Failed -> {
                    assertEquals(outcome, outcome.copy())
                    assertEquals(outcome.hashCode(), outcome.copy().hashCode())
                    assertNotEquals(outcome, outcome.copy(message = (outcome.message ?: "") + "x"))
                }
            }
        }
    }

    @Test
    fun `open chat and failed retain their payloads`() {
        assertEquals("", Outcome.OpenChat("").chatId)
        assertEquals(null, Outcome.Failed(null).message)
        assertEquals("", Outcome.Failed("").message)
        assertNotEquals(Outcome.Failed(null), Outcome.Failed(""))
        assertNotEquals(Outcome.Failed(""), Outcome.Failed("x"))
        assertNotEquals(Outcome.OpenChat("a"), Outcome.OpenChat("b"))
        assertNotEquals<Outcome>(Outcome.SessionExpired, Outcome.Dropped)
        repeat(200) {
            val outcome = randomOutcome()
            when (outcome) {
                is Outcome.OpenChat -> assertEquals(outcome.chatId, Outcome.OpenChat(outcome.chatId).chatId)
                is Outcome.Failed -> assertEquals(outcome.message, Outcome.Failed(outcome.message).message)
                else -> Unit
            }
        }
    }

    @Test
    fun `exhaustive when labels every outcome variant`() {
        repeat(200) {
            val outcome = randomOutcome()
            val expected = when (outcome) {
                Outcome.SessionExpired -> "expired"
                Outcome.Dropped -> "dropped"
                is Outcome.OpenChat -> "open:${outcome.chatId}"
                is Outcome.Failed -> "failed:${outcome.message}"
            }
            assertEquals(expected, label(outcome))
        }
        assertEquals("expired", label(Outcome.SessionExpired))
        assertEquals("dropped", label(Outcome.Dropped))
        assertEquals("open:c1", label(Outcome.OpenChat("c1")))
        assertEquals("failed:null", label(Outcome.Failed(null)))
    }
}
