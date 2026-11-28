package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// isSettablePin restated by hand: a settable PIN is 4..8 UTF-16
// units long; the content does not matter, only the length.
internal fun isSettablePinOracle175(pin: String): Boolean =
    pin.length in 4..8

// shouldRetry restated by hand: the first three Worker attempts may
// retry, the fourth and later are a failure.
internal fun shouldRetryOracle175(runAttemptCount: Int): Boolean =
    runAttemptCount < 3

// parseProtocol restated by hand: trim, uppercase, then the alias
// table; anything unrecognised (including null) is the default
// OpenAI chat completions protocol.
internal fun parseProtocolOracle175(raw: String?): LocalAiProtocol =
    when (raw?.trim()?.uppercase()) {
        "OPENAI_RESPONSES", "RESPONSES", "RESPONSE" -> LocalAiProtocol.OPENAI_RESPONSES
        "ANTHROPIC_MESSAGES", "ANTHROPIC", "CLAUDE" -> LocalAiProtocol.ANTHROPIC_MESSAGES
        else -> LocalAiProtocol.OPENAI_CHAT_COMPLETIONS
    }

// Production isSettablePin checks the settable PIN length.
internal fun isSettablePinProduction175(pin: String): Boolean =
    ChatLockPinPolicy.isSettablePin(pin)

// Production shouldRetry checks the Worker retry threshold.
internal fun shouldRetryProduction175(runAttemptCount: Int): Boolean =
    AiTaskReminderRetryPolicy.shouldRetry(runAttemptCount)

// Production parseProtocol parses the provider protocol name.
internal fun parseProtocolProduction175(raw: String?): LocalAiProtocol =
    LocalAiProtocolCodec.parseProtocol(raw)

// ---------- generators ----------

internal data class SmallGateCase175(
    val pin: String,
    val attempt: Int,
    val raw: String?,
)

internal val SMALL_GATE_PIN_POOL175: List<String> = listOf(
    "", "1", "12", "123", "1234", "12345", "123456", "1234567", "12345678",
    "123456789", "1234567890", "    ", "abcd", "abcdefgh", "abcdefghi",
    "🎉🎉", "🎉🎉🎉🎉", "🎉🎉🎉🎉🎉", "a🎉b🎉", "\u00a0\u00a0\u00a0\u00a0",
)

internal val SMALL_GATE_ATTEMPT_POOL175: List<Int> = listOf(
    Int.MIN_VALUE, -100, -1, 0, 1, 2, 3, 4, 5, 100, Int.MAX_VALUE
)

internal val SMALL_GATE_RAW_POOL175: List<String?> = listOf(
    null, "", " ", "  ", "responses", "RESPONSES", "Response", " response ",
    "RESPONSE", "anthropic", "ANTHROPIC", "claude", "CLAUDE", "chat",
    "openai_chat_completions", "OPENAI_CHAT_COMPLETIONS", "openai_responses",
    "ANTHROPIC_MESSAGES", "  anthropic  ", "respönses", "respon\tses",
    "responsex", "xresponse", "claude-3", "ANTHROPIC_MESSAGES ",
    "\nresponses\n", "responses\u00a0",
)

internal fun randomSmallGateCase175(rnd: Random): SmallGateCase175 {
    val pinLen = rnd.nextInt(0, 13)
    val pinSb = StringBuilder()
    val pinAlphabet = "12345678ab🎉 \u00a0"
    repeat(pinLen) {
        pinSb.append(pinAlphabet[rnd.nextInt(pinAlphabet.length)])
    }
    val raw = if (rnd.nextInt(4) == 0) {
        SMALL_GATE_RAW_POOL175[rnd.nextInt(SMALL_GATE_RAW_POOL175.size)]
    } else {
        val words = listOf("responses", "anthropic", "claude", "chat", "openai", "x")
        val w = words[rnd.nextInt(words.size)]
        if (rnd.nextBoolean()) w.uppercase() else w
    }
    return SmallGateCase175(
        pin = pinSb.toString(),
        attempt = SMALL_GATE_ATTEMPT_POOL175[rnd.nextInt(SMALL_GATE_ATTEMPT_POOL175.size)],
        raw = raw,
    )
}

// Canonical JSON string escaping, same as Python json.dumps with
// ensure_ascii=False, so wire JSON survives quotes and control chars.
internal fun escJsonSmallGate175(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

internal fun escJsonOrNullSmallGate175(s: String?): String =
    s?.let { escJsonSmallGate175(it) } ?: "null"

internal fun smallGateWireJson175(c: SmallGateCase175): String =
    "{\"pin\":${escJsonSmallGate175(c.pin)}," +
        "\"attempt\":${c.attempt}," +
        "\"raw\":${escJsonOrNullSmallGate175(c.raw)}}"

internal fun smallGateProjectionJson175(settable: Boolean, retry: Boolean, protocol: LocalAiProtocol): String =
    "{\"settable\":$settable,\"retry\":$retry," +
        "\"protocol\":${escJsonSmallGate175(protocol.name)}}"

class ClientSmallGatesFuzzTest {

    private fun checkSmallGate175(c: SmallGateCase175) {
        assertEquals(
            smallGateProjectionJson175(
                isSettablePinOracle175(c.pin),
                shouldRetryOracle175(c.attempt),
                parseProtocolOracle175(c.raw)
            ),
            smallGateProjectionJson175(
                isSettablePinProduction175(c.pin),
                shouldRetryProduction175(c.attempt),
                parseProtocolProduction175(c.raw)
            ),
            "smallGate($c)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        // PIN lengths around the 4..8 window (astral pins count
        // double, so two emoji are only just settable); retry
        // attempts around the 3-attempt cut; protocol aliases in
        // every case and with padding.
        for (pin in SMALL_GATE_PIN_POOL175) {
            for (attempt in listOf(-1, 0, 2, 3, 4)) {
                for (raw in listOf<String?>(null, "responses", "anthropic", "chat")) {
                    checkSmallGate175(SmallGateCase175(pin, attempt, raw))
                }
            }
        }
        for (raw in SMALL_GATE_RAW_POOL175) {
            checkSmallGate175(SmallGateCase175("1234", 0, raw))
        }
        for (attempt in SMALL_GATE_ATTEMPT_POOL175) {
            checkSmallGate175(SmallGateCase175("1234", attempt, "responses"))
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(175003)
        repeat(4000) { checkSmallGate175(randomSmallGateCase175(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // Full cross product of the three pools: every PIN length
        // shape x every attempt boundary x every protocol spelling.
        for (pin in SMALL_GATE_PIN_POOL175) {
            for (attempt in SMALL_GATE_ATTEMPT_POOL175) {
                for (raw in SMALL_GATE_RAW_POOL175) {
                    checkSmallGate175(SmallGateCase175(pin, attempt, raw))
                }
            }
        }
    }
}
