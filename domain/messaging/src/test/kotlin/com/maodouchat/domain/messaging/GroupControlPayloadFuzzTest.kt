package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// decideGroupControlPayload restated by hand: only an exact
// "SENDER_KEY" kind takes the decoded body as a Sender Key
// distribution; anything else is a key request.
internal fun decideGroupControlPayloadOracle175(kind: String): GroupControlPayload =
    if (kind == "SENDER_KEY") GroupControlPayload(useDecodedBody = true, payloadType = "SK_DIST")
    else GroupControlPayload(useDecodedBody = false, payloadType = "KEY_REQUEST")

// Production decideGroupControlPayload decides the group-control payload.
internal fun decideGroupControlPayloadProduction175(kind: String): GroupControlPayload =
    decideGroupControlPayload(kind)

// ---------- generators ----------

internal data class GroupControlCase175(
    val kind: String,
)

internal val GROUP_CONTROL_KIND_POOL175: List<String> = listOf(
    "SENDER_KEY", "KEY_REQUEST", "DATA", "", "sender_key",
    "SENDER_KEY ", " SENDER_KEY", "SENDER-KEY", "SK_DIST",
    "SENDER_KEY\n", "SENDER_KEY\u0000", "SENDER_KEYX", "XSENDER_KEY",
    "SENDER_KEY\t", "sender-Key", "SENDER__KEY",
)

internal val GROUP_CONTROL_ALPHABET175: String = "SENDER_KEY_ senderkey0123"

internal fun randomGroupControlCase175(rnd: Random): GroupControlCase175 {
    if (rnd.nextInt(3) == 0) {
        return GroupControlCase175(
            GROUP_CONTROL_KIND_POOL175[rnd.nextInt(GROUP_CONTROL_KIND_POOL175.size)]
        )
    }
    val len = rnd.nextInt(0, 14)
    val sb = StringBuilder()
    repeat(len) {
        sb.append(GROUP_CONTROL_ALPHABET175[rnd.nextInt(GROUP_CONTROL_ALPHABET175.length)])
    }
    return GroupControlCase175(sb.toString())
}

// Canonical JSON string escaping, same as Python json.dumps with
// ensure_ascii=False, so wire JSON survives quotes and control chars.
internal fun escJsonGroupControl175(s: String): String {
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

internal fun groupControlWireJson175(c: GroupControlCase175): String =
    "{\"kind\":${escJsonGroupControl175(c.kind)}}"

internal fun groupControlProjectionJson175(g: GroupControlPayload): String =
    "{\"useDecodedBody\":${g.useDecodedBody}," +
        "\"payloadType\":${escJsonGroupControl175(g.payloadType)}}"

class GroupControlPayloadFuzzTest {

    private fun checkGroupControl175(c: GroupControlCase175) {
        assertEquals(
            groupControlProjectionJson175(decideGroupControlPayloadOracle175(c.kind)),
            groupControlProjectionJson175(decideGroupControlPayloadProduction175(c.kind)),
            "groupControl($c)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        // The exact kind, its near misses (case, padding, prefix and
        // suffix noise), and the empty kind all go through the same
        // one-branch decision.
        for (kind in GROUP_CONTROL_KIND_POOL175) {
            checkGroupControl175(GroupControlCase175(kind))
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(175002)
        repeat(4000) { checkGroupControl175(randomGroupControlCase175(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // Every pooled kind, plus every one- and two-letter string
        // over the kind alphabet's core letters (catches prefix,
        // case and padding slips the pool does not spell out).
        for (kind in GROUP_CONTROL_KIND_POOL175) {
            checkGroupControl175(GroupControlCase175(kind))
        }
        val letters = "SEDKY_ "
        for (a in letters) {
            checkGroupControl175(GroupControlCase175(a.toString()))
            for (b in letters) {
                checkGroupControl175(GroupControlCase175("$a$b"))
            }
        }
    }
}
