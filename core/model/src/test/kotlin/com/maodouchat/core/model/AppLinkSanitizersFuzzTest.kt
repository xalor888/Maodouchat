// AppLinkRouter sanitizers + path encoder (142nd block). Oracle
// reimplements the helpers from production text; the scheme parsers
// that call them were covered by the 141st block.
package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- sanitizer oracles ----------

private val SAN_HEX = "0123456789ABCDEF".toCharArray()

private fun sanitizeHttpUrlOracle(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.length > AppLinkRouter.MAX_USER_FACING_URL_LENGTH) return null
    if (trimmed.any { it.isISOControl() || it.isWhitespace() }) return null
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return null
    val afterScheme = trimmed.substring(schemeEnd + 3)
    if (afterScheme.isEmpty() || afterScheme.startsWith('/')) return null
    return trimmed
}

private fun sanitizeChatInviteTokenOracle(raw: String): String? {
    val single = raw.trim()
    if (single.any { it == '/' || it == '?' || it == '#' }) return null
    if (single.length !in AppLinkRouter.MIN_CHAT_INVITE_TOKEN_LENGTH..AppLinkRouter.MAX_INVITE_CODE_LENGTH) return null
    if (single.any { c -> !(c.isLetterOrDigit() || c == '_' || c == '-') }) return null
    return single
}

private fun sanitizeOpaqueIdOracle(raw: String): String? {
    val single = raw.substringBefore('/').substringBefore('?').substringBefore('#').trim()
        .take(AppLinkRouter.MAX_ID_LENGTH)
    if (single.isBlank()) return null
    if (single.any { c -> c.isWhitespace() || c.code < 0x20 }) return null
    return single
}

private fun sanitizeChatIdStrictOracle(raw: String): String? =
    if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeOpaqueIdOracle(raw)

private fun sanitizeMessageIdStrictOracle(raw: String): String? =
    if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeOpaqueIdOracle(raw)

private fun sanitizePostIdStrictOracle(raw: String): String? =
    if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeOpaqueIdOracle(raw)

private fun sanitizeCallIdStrictOracle(raw: String): String? =
    if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeOpaqueIdOracle(raw)

private fun sanitizeUserIdStrictOracle(raw: String): String? =
    if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeOpaqueIdOracle(raw)

private fun encodePathSegmentOracle(raw: String): String {
    val sb = StringBuilder()
    for (ch in raw) {
        if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
            sb.append(ch)
        } else {
            for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                sb.append('%')
                sb.append(SAN_HEX[(b.toInt() shr 4) and 0xF])
                sb.append(SAN_HEX[b.toInt() and 0xF])
            }
        }
    }
    return sb.toString()
}

// ---------- generators (mirrored by Oracle142 GEN, seed 142002) ----------

private val SAN_POOL = arrayOf(
    "", " ", "   ", "http://a", "https://example.com/x", "HTTP://A",
    "HtTp://a/b?c=d", "http://", "http:///", "://a", "ftp://a", "http:a",
    "http://a b", "http://a/b?c=d", "http://a\u00A0b", "http://a\u200Bb",
    "http://a\u0000b", "http://a\u000Eb", "İ://a", "K://a", "  http://a  ", "a".repeat(31),
    "a".repeat(32), "a".repeat(43), "a".repeat(80), "a".repeat(81),
    "abc-123_~", "a/b", "a?b", "a#b", "a b", "a!b", "é", "a\u0000b",
    "  padded  ", "u_abc-123", "550e8400-e29b-41d4-a716-446655440000",
    "maodouchat:chat-invite:v1:abc", "x".repeat(129), "x".repeat(128),
    "http://" + "x".repeat(2041), "http://" + "x".repeat(2042),
    "a/b/c", "a?b?c", "a#b#c", "İ", "ı", "K", "Σ", "ß", "ª", "²",
    "a\u007Fb", "a\u0085b", "a\u00A0b", "a\u2003b", "a\u3000b",
)
private val SAN_URL_HEAD_POOL = arrayOf(
    "http://", "https://", "HTTP://", "ftp://", "", "http:/", "http:///",
    "HtTpS://", "://",
)
private val SAN_ALPHABET = "abcXYZ019_-./?#%+ :~"

private fun randomSanText142(rnd: Random, maxLen: Int = 12): String {
    val sb = StringBuilder()
    val len = rnd.nextInt(0, maxLen + 1)
    repeat(len) {
        when (rnd.nextInt(6)) {
            0 -> sb.append(('a' + rnd.nextInt(26)))
            1 -> sb.append(('0' + rnd.nextInt(10)))
            2 -> sb.append(SAN_ALPHABET[rnd.nextInt(SAN_ALPHABET.length)])
            3 -> sb.appendCodePoint(rnd.nextInt(0x20, 0x2FFF))
            4 -> sb.appendCodePoint(rnd.nextInt(0x10000, 0x1FFFF))
            else -> sb.append(SAN_POOL[rnd.nextInt(SAN_POOL.size)])
        }
    }
    return sb.toString()
}

private fun randomSanInput(rnd: Random): String = when (rnd.nextInt(4)) {
    0 -> SAN_POOL[rnd.nextInt(SAN_POOL.size)]
    1 -> SAN_URL_HEAD_POOL[rnd.nextInt(SAN_URL_HEAD_POOL.size)] + randomSanText142(rnd, 10)
    2 -> randomSanText142(rnd, 16)
    else -> "a".repeat(rnd.nextInt(0, 90))
}

class AppLinkSanitizersFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val inputs = listOf(
            "",
            " ",
            "   ",
            "http://a",
            "https://example.com/x",
            "HTTP://A",
            "HtTp://a/b?c=d",
            "http://",
            "http:///",
            "://a",
            "ftp://a",
            "http:a",
            "http://a b",
            "http://a/b?c=d",
            "http://a\u00A0b",
            "http://a\u200Bb",
            "http://a\u0000b",
            "http://a\u000Eb",
            "İ://a",
            "K://a",
            "  http://a  ",
            "a".repeat(31),
            "a".repeat(32),
            "a".repeat(43),
            "a".repeat(80),
            "a".repeat(81),
            "abc-123_~",
            "a/b",
            "a?b",
            "a#b",
            "a b",
            "a!b",
            "é",
            "a\u0000b",
            "  padded  ",
            "u_abc-123",
            "550e8400-e29b-41d4-a716-446655440000",
            "maodouchat:chat-invite:v1:abc",
            "x".repeat(129),
            "x".repeat(128),
            "http://" + "x".repeat(2041),
            "http://" + "x".repeat(2042),
            "a/b/c",
            "a?b?c",
            "a#b#c",
            "İ",
            "ı",
            "K",
            "Σ",
            "ß",
            "ª",
            "²",
            "a\u007Fb",
            "a\u0085b",
            "a\u00A0b",
            "a\u2003b",
            "a\u3000b",
        )
        for (input in inputs) {
            assertEquals(
                sanitizeHttpUrlOracle(input),
                with(AppLinkRouter) { sanitizeHttpUrl(input) },
                "httpUrl input=$input",
            )
            assertEquals(
                sanitizeChatInviteTokenOracle(input),
                with(AppLinkRouter) { sanitizeChatInviteToken(input) },
                "inviteToken input=$input",
            )
            assertEquals(
                sanitizeChatIdStrictOracle(input),
                with(AppLinkRouter) { sanitizeChatIdStrict(input) },
                "chatStrict input=$input",
            )
            assertEquals(
                sanitizeMessageIdStrictOracle(input),
                with(AppLinkRouter) { sanitizeMessageIdStrict(input) },
                "msgStrict input=$input",
            )
            assertEquals(
                sanitizePostIdStrictOracle(input),
                with(AppLinkRouter) { sanitizePostIdStrict(input) },
                "postStrict input=$input",
            )
            assertEquals(
                sanitizeCallIdStrictOracle(input),
                with(AppLinkRouter) { sanitizeCallIdStrict(input) },
                "callStrict input=$input",
            )
            assertEquals(
                sanitizeUserIdStrictOracle(input),
                with(AppLinkRouter) { sanitizeUserIdStrict(input) },
                "userStrict input=$input",
            )
            assertEquals(
                encodePathSegmentOracle(input),
                with(AppLinkRouter) { encodePathSegment(input) },
                "encode input=$input",
            )
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(142002)
        repeat(1500) { i ->
            val input = randomSanInput(rnd)
            assertEquals(
                sanitizeHttpUrlOracle(input),
                with(AppLinkRouter) { sanitizeHttpUrl(input) },
                "httpUrl iter=$i input=$input",
            )
            assertEquals(
                sanitizeChatInviteTokenOracle(input),
                with(AppLinkRouter) { sanitizeChatInviteToken(input) },
                "inviteToken iter=$i input=$input",
            )
            assertEquals(
                sanitizeChatIdStrictOracle(input),
                with(AppLinkRouter) { sanitizeChatIdStrict(input) },
                "chatStrict iter=$i input=$input",
            )
            assertEquals(
                sanitizeMessageIdStrictOracle(input),
                with(AppLinkRouter) { sanitizeMessageIdStrict(input) },
                "msgStrict iter=$i input=$input",
            )
            assertEquals(
                sanitizePostIdStrictOracle(input),
                with(AppLinkRouter) { sanitizePostIdStrict(input) },
                "postStrict iter=$i input=$input",
            )
            assertEquals(
                sanitizeCallIdStrictOracle(input),
                with(AppLinkRouter) { sanitizeCallIdStrict(input) },
                "callStrict iter=$i input=$input",
            )
            assertEquals(
                sanitizeUserIdStrictOracle(input),
                with(AppLinkRouter) { sanitizeUserIdStrict(input) },
                "userStrict iter=$i input=$input",
            )
            assertEquals(
                encodePathSegmentOracle(input),
                with(AppLinkRouter) { encodePathSegment(input) },
                "encode iter=$i input=$input",
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        fun editsOf(target: String): List<String> {
            val out = mutableListOf<String>()
            val edits = " _Xa0\u0000"
            for (pos in target.indices) {
                for (rep in edits) out.add(target.substring(0, pos) + rep + target.substring(pos + 1))
                out.add(target.substring(0, pos) + target.substring(pos + 1))
                val ch = target[pos]
                out.add(target.substring(0, pos) + (if (ch.isUpperCase()) ch.lowercaseChar() else ch.uppercaseChar()) + target.substring(pos + 1))
            }
            for (pos in 0..target.length) {
                for (ins in listOf("_", "X")) out.add(target.substring(0, pos) + ins + target.substring(pos))
            }
            return out
        }
        fun checkAll(input: String, tag: String) {
            assertEquals(
                sanitizeHttpUrlOracle(input),
                with(AppLinkRouter) { sanitizeHttpUrl(input) },
                "httpUrl $tag input=$input",
            )
            assertEquals(
                sanitizeChatInviteTokenOracle(input),
                with(AppLinkRouter) { sanitizeChatInviteToken(input) },
                "inviteToken $tag input=$input",
            )
            assertEquals(
                sanitizeChatIdStrictOracle(input),
                with(AppLinkRouter) { sanitizeChatIdStrict(input) },
                "chatStrict $tag input=$input",
            )
            assertEquals(
                sanitizeMessageIdStrictOracle(input),
                with(AppLinkRouter) { sanitizeMessageIdStrict(input) },
                "msgStrict $tag input=$input",
            )
            assertEquals(
                sanitizePostIdStrictOracle(input),
                with(AppLinkRouter) { sanitizePostIdStrict(input) },
                "postStrict $tag input=$input",
            )
            assertEquals(
                sanitizeCallIdStrictOracle(input),
                with(AppLinkRouter) { sanitizeCallIdStrict(input) },
                "callStrict $tag input=$input",
            )
            assertEquals(
                sanitizeUserIdStrictOracle(input),
                with(AppLinkRouter) { sanitizeUserIdStrict(input) },
                "userStrict $tag input=$input",
            )
            assertEquals(
                encodePathSegmentOracle(input),
                with(AppLinkRouter) { encodePathSegment(input) },
                "encode $tag input=$input",
            )
        }
        for (base in listOf(
            "http://example.com/x", "abc-123_~", "c1", "a/b",
            "HTTP://A", "maodouchat:chat-invite:v1:abc",
        )) {
            for (input in editsOf(base)) checkAll(input, "sweep")
        }
        for (code in 0x00..0x7F) {
            checkAll(String(Character.toChars(code)), "single-unit code=$code")
        }
    }
}
