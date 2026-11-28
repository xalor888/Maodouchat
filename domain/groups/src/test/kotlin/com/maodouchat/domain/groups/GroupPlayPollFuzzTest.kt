package com.maodouchat.domain.groups

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// kotlinx.serialization JSON string escaping, restated by hand: only
// '"', '\' and the control chars are escaped (\n \r \t \b \f short,
// anything else below 0x20 as \uXXXX in lowercase hex); every other
// char, including non-ASCII and lone surrogates, goes out raw.
internal fun escKotlinJson176(s: String): String {
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

// buildPollPayload restated by hand: the question is capped at 200
// UTF-16 units, each option at 80, and the payload is the POLL:
// prefix plus one compact JSON object in id/q/options/multi/
// anonymous order.
internal fun buildPollPayloadOracle176(
    pollId: String,
    question: String,
    options: List<String>,
    multi: Boolean,
    anonymous: Boolean,
): String {
    val opts = options.joinToString(",") { escKotlinJson176(it.take(80)) }
    return "POLL:{\"id\":${escKotlinJson176(pollId)}," +
        "\"q\":${escKotlinJson176(question.take(200))}," +
        "\"options\":[$opts]," +
        "\"multi\":$multi," +
        "\"anonymous\":$anonymous}"
}

// parsePoll restated by hand: strip the POLL: prefix and parse the
// rest as JSON, requiring a top-level object. The parse itself is the
// kotlinx parser, shared with production by design -- the differential
// that matters here is the Python lenient-JSON model against this
// library behaviour, not a second hand parser.
internal fun parsePollOracle176(content: String): JsonObject? {
    if (!content.startsWith("POLL:")) return null
    return runCatching {
        Json.parseToJsonElement(content.removePrefix("POLL:")).jsonObject
    }.getOrNull()
}

// Production buildPollPayload builds the poll payload string.
internal fun buildPollPayloadProduction176(
    pollId: String,
    question: String,
    options: List<String>,
    multi: Boolean,
    anonymous: Boolean,
): String = GroupPlayPolicy.buildPollPayload(pollId, question, options, multi, anonymous)

// Production parsePoll parses a POLL: payload back to a JSON object.
internal fun parsePollProduction176(content: String): JsonObject? =
    GroupPlayPolicy.parsePoll(content)

// ---------- generators ----------

internal data class PollBuildCase176(
    val pollId: String,
    val question: String,
    val options: List<String>,
    val multi: Boolean,
    val anonymous: Boolean,
)

internal data class PollParseCase176(
    val content: String,
)

internal val POLL_ID_POOL176: List<String> = listOf(
    "", "p1", "poll-1", "a".repeat(200), "id with spaces", "id\"quote",
    "id\\slash", "id\nnewline", "p\u00e9", "POLL:", "123",
)

internal val POLL_QUESTION_POOL176: List<String> = listOf(
    "", " ", "Which one?", "a".repeat(199), "a".repeat(200),
    "a".repeat(201), "a".repeat(500), "q\"uote", "q\\slash",
    "q\nline", "q\ttab", "q\u0001ctrl", "caf\u00e9?", "\uD83D\uDE00 poll",
    "a\uD800b", "x".repeat(200) + "y",
)

internal val POLL_OPTION_POOL176: List<String> = listOf(
    "", "yes", "no", "maybe", "a".repeat(79), "a".repeat(80),
    "a".repeat(81), "a".repeat(200), "opt\"ion", "opt\\ion",
    "opt\nion", "opt\u0002ion", "caf\u00e9", "\uD83D\uDE00",
)

internal val POLL_PARSE_POOL176: List<String> = listOf(
    "", "POLL:", "POLL:{}", "POLL:{\"id\":\"p1\"}",
    "POLL:{\"id\":\"p1\",\"q\":\"Which?\",\"options\":[\"a\",\"b\"],\"multi\":false,\"anonymous\":true}",
    "POLL:{\"id\":\"p1\",\"id\":\"p2\"}",
    "POLL:{\"a\":1}", "POLL:{\"a\":1.0}", "POLL:{\"a\":1e3}",
    "POLL:{\"a\":0.10}", "POLL:{\"a\":-0}",
    "POLL:{\"a\":123456789012345678901234567890}",
    "POLL:{\"a\":NaN}", "POLL:{\"a\":Infinity}", "POLL:{\"a\":-Infinity}",
    "POLL:{\"a\":01}", "POLL:{\"a\":.5}", "POLL:{\"a\":1,}",
    "POLL:{\"a\":\"\\x\"}", "POLL:{bad", "POLL:[1,2]", "POLL:\"str\"",
    "POLL:123", "POLL:{\"a\":\"x\\ud800y\"}", "POLL:{\"a\":1.5e-3}",
    "POLL:{\"b\":1,\"a\":\"x\\n\"}", "POLL: {\"a\":1}", "POLL:{\"a\":1} ",
    "POLL:{\"a\":true,\"b\":null}", "POLL:{\"a\":[1,{\"b\":2}]}",
    "POLL:{\"a\":\"\"}", "POLL:{\"\":1}", "poll:{\"a\":1}",
    "POLL:{\"a\":1} trailing", "XPOLL:{\"a\":1}",
)

internal val POLL_ALPHABET176: String =
    "abcXYZ 0123456789_{}\":,[]\"\\\n\t\u00e9\uD83D\uDE00"

internal fun randomPollBuildCase176(rnd: Random): PollBuildCase176 {
    fun randomText(maxLen: Int): String {
        if (rnd.nextInt(3) == 0) {
            return POLL_QUESTION_POOL176[rnd.nextInt(POLL_QUESTION_POOL176.size)]
        }
        val len = rnd.nextInt(0, maxLen)
        val sb = StringBuilder()
        repeat(len) {
            sb.append(POLL_ALPHABET176[rnd.nextInt(POLL_ALPHABET176.length)])
        }
        return sb.toString()
    }
    val nOptions = rnd.nextInt(0, 5)
    val options = ArrayList<String>(nOptions)
    repeat(nOptions) { options.add(randomText(100)) }
    return PollBuildCase176(
        pollId = randomText(30),
        question = randomText(260),
        options = options,
        multi = rnd.nextBoolean(),
        anonymous = rnd.nextBoolean()
    )
}

internal fun randomPollParseCase176(rnd: Random): PollParseCase176 {
    if (rnd.nextInt(2) == 0) {
        return PollParseCase176(POLL_PARSE_POOL176[rnd.nextInt(POLL_PARSE_POOL176.size)])
    }
    val built = randomPollBuildCase176(rnd)
    val payload = buildPollPayloadOracle176(
        built.pollId, built.question, built.options, built.multi, built.anonymous
    )
    // Half the time, corrupt the built payload by one edit.
    if (rnd.nextInt(2) == 0 || payload.length < 8) return PollParseCase176(payload)
    val pos = rnd.nextInt(payload.length)
    val edit = rnd.nextInt(3)
    val corrupted = when (edit) {
        0 -> payload.removeRange(pos, pos + 1)
        1 -> payload.substring(0, pos) + "x" + payload.substring(pos)
        else -> payload.substring(0, pos) + "\"" + payload.substring(pos)
    }
    return PollParseCase176(corrupted)
}

internal fun escJsonPoll176(s: String): String {
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

internal fun pollBuildWireJson176(c: PollBuildCase176): String {
    val opts = c.options.joinToString(",") { escJsonPoll176(it) }
    return "{\"pollId\":${escJsonPoll176(c.pollId)}," +
        "\"question\":${escJsonPoll176(c.question)}," +
        "\"options\":[$opts]," +
        "\"multi\":${c.multi}," +
        "\"anonymous\":${c.anonymous}}"
}

internal fun pollParseWireJson176(c: PollParseCase176): String =
    "{\"content\":${escJsonPoll176(c.content)}}"

class GroupPlayPollFuzzTest {

    private fun checkPollBuild176(c: PollBuildCase176) {
        assertEquals(
            buildPollPayloadOracle176(c.pollId, c.question, c.options, c.multi, c.anonymous),
            buildPollPayloadProduction176(c.pollId, c.question, c.options, c.multi, c.anonymous),
            "pollBuild($c)"
        )
    }

    private fun checkPollParse176(c: PollParseCase176) {
        assertEquals(
            parsePollOracle176(c.content)?.toString(),
            parsePollProduction176(c.content)?.toString(),
            "pollParse($c)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Build: every pooled id/question/option at the truncation
        // edges, both flags; parse: every pooled payload shape,
        // including the lenient-number forms the kotlinx parser
        // accepts (NaN, 01, .5) and the shapes it rejects.
        for (id in POLL_ID_POOL176) {
            for (question in POLL_QUESTION_POOL176) {
                checkPollBuild176(
                    PollBuildCase176(id, question, listOf("yes", "no"), false, false)
                )
            }
        }
        for (option in POLL_OPTION_POOL176) {
            checkPollBuild176(
                PollBuildCase176("p1", "Which?", listOf(option), true, true)
            )
        }
        for (content in POLL_PARSE_POOL176) {
            checkPollParse176(PollParseCase176(content))
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(176002)
        repeat(2000) { checkPollBuild176(randomPollBuildCase176(rnd)) }
        repeat(2000) { checkPollParse176(randomPollParseCase176(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // Build: every pooled question against every pooled option
        // list shape; parse: every pooled payload, plus every one-
        // and two-char string over the JSON alphabet after POLL:.
        for (question in POLL_QUESTION_POOL176) {
            for (options in listOf(
                emptyList(), listOf("a"), listOf("a", "b"),
                POLL_OPTION_POOL176.take(3)
            )) {
                checkPollBuild176(PollBuildCase176("p1", question, options, false, true))
            }
        }
        for (content in POLL_PARSE_POOL176) {
            checkPollParse176(PollParseCase176(content))
        }
        val letters = "{}[]\"a1:,. "
        for (a in letters) {
            checkPollParse176(PollParseCase176("POLL:$a"))
            for (b in letters) {
                checkPollParse176(PollParseCase176("POLL:$a$b"))
            }
        }
    }
}
