// AppLinkRouter scheme-internal parsers (141st block). Oracle
// reimplements the parsers from production text; parseDeepLink /
// resolveUserFacingUrl were covered by the 29th block.
package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- sanitizer oracles ----------

private val AL_HEX = "0123456789ABCDEF".toCharArray()

private fun decodeComponentOracle(raw: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '%' && i + 2 < raw.length) {
            val hi = AL_HEX.indexOf(raw[i + 1].uppercaseChar())
            val lo = AL_HEX.indexOf(raw[i + 2].uppercaseChar())
            if (hi >= 0 && lo >= 0) {
                out.append(((hi shl 4) or lo).toChar())
                i += 3
                continue
            }
        }
        out.append(if (c == '+') ' ' else c)
        i++
    }
    return out.toString()
}

private fun parseQueryParamOracle(query: String, key: String): String? {
    if (query.isEmpty()) return null
    for (pair in query.split('&')) {
        val eq = pair.indexOf('=')
        if (eq <= 0) continue
        if (pair.substring(0, eq) == key) {
            return decodeComponentOracle(pair.substring(eq + 1)).takeIf { it.isNotEmpty() }
        }
    }
    return null
}

private fun sanitizeOpaqueIdOracle(raw: String): String? {
    val single = raw.substringBefore('/').substringBefore('?').substringBefore('#').trim()
        .take(AppLinkRouter.MAX_ID_LENGTH)
    if (single.isBlank()) return null
    if (single.any { c -> c.isWhitespace() || c.code < 0x20 }) return null
    return single
}

private fun sanitizeUsernameOracle(raw: String): String? {
    val single = raw.substringBefore('/').substringBefore('?').substringBefore('#').trim()
        .take(AppLinkRouter.MAX_USERNAME_LENGTH)
    if (single.isBlank()) return null
    if (single.any { c -> !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.') }) return null
    return single
}

private fun sanitizeInviteCodeOracle(raw: String): String? {
    val single = raw.substringBefore('/').substringBefore('?').substringBefore('#').trim()
        .take(AppLinkRouter.MAX_INVITE_CODE_LENGTH)
    if (single.isBlank()) return null
    if (single.any { c -> !(c.isLetterOrDigit() || c == '_' || c == '-') }) return null
    return single
}

private fun parseMaodouSchemeOracle(afterScheme: String): AppLinkParseResult {
    val path = afterScheme.substringBefore('?')
    val query = afterScheme.substringAfter('?', "")
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return AppLinkParseResult.Rejected("empty_path")
    return when (segments[0]) {
        "u" -> {
            val username = sanitizeUsernameOracle(segments.drop(1).firstOrNull().orEmpty())
                ?: return AppLinkParseResult.Rejected("bad_username")
            if (segments.size > 2) return AppLinkParseResult.Rejected("bad_username")
            AppLinkParseResult.Accepted(AppLinkDestination.PublicProfile(username))
        }
        "chat" -> {
            val chatId = sanitizeOpaqueIdOracle(segments.drop(1).firstOrNull().orEmpty())
                ?: return AppLinkParseResult.Rejected("bad_chat")
            if (segments.size > 2) return AppLinkParseResult.Rejected("bad_chat")
            val messageId = parseQueryParamOracle(query, "messageId")?.let {
                sanitizeOpaqueIdOracle(it) ?: return AppLinkParseResult.Rejected("bad_message")
            }
            AppLinkParseResult.Accepted(AppLinkDestination.ChatDetail(chatId, messageId))
        }
        "post" -> {
            val postId = sanitizeOpaqueIdOracle(segments.drop(1).firstOrNull().orEmpty())
                ?: return AppLinkParseResult.Rejected("bad_post")
            if (segments.size > 2) return AppLinkParseResult.Rejected("bad_post")
            val comment = parseQueryParamOracle(query, "comment")?.let {
                sanitizeOpaqueIdOracle(it) ?: return AppLinkParseResult.Rejected("bad_comment")
            }
            AppLinkParseResult.Accepted(AppLinkDestination.PostDetail(postId, comment))
        }
        "invite" -> {
            val code = sanitizeInviteCodeOracle(segments.drop(1).firstOrNull().orEmpty())
                ?: return AppLinkParseResult.Rejected("bad_invite")
            if (segments.size > 2) return AppLinkParseResult.Rejected("bad_invite")
            AppLinkParseResult.Accepted(AppLinkDestination.GroupInvite(code))
        }
        else -> AppLinkParseResult.Rejected("unsupported")
    }
}

private fun parseHttpsChatMdouOracle(pathAndQuery: String): AppLinkParseResult {
    val path = pathAndQuery.substringBefore('?')
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return AppLinkParseResult.Rejected("empty_path")
    return when (segments[0]) {
        "u" -> {
            val username = sanitizeUsernameOracle(segments.drop(1).firstOrNull().orEmpty())
                ?: return AppLinkParseResult.Rejected("bad_username")
            if (segments.size > 2) return AppLinkParseResult.Rejected("bad_username")
            AppLinkParseResult.Accepted(AppLinkDestination.PublicProfile(username))
        }
        "c" -> {
            val chatId = sanitizeOpaqueIdOracle(segments.drop(1).firstOrNull().orEmpty())
                ?: return AppLinkParseResult.Rejected("bad_chat")
            if (segments.size > 2) return AppLinkParseResult.Rejected("bad_chat")
            AppLinkParseResult.Accepted(AppLinkDestination.ChatDetail(chatId))
        }
        "join" -> {
            val code = sanitizeInviteCodeOracle(segments.drop(1).firstOrNull().orEmpty())
                ?: return AppLinkParseResult.Rejected("bad_invite")
            if (segments.size > 2) return AppLinkParseResult.Rejected("bad_invite")
            AppLinkParseResult.Accepted(AppLinkDestination.GroupInvite(code))
        }
        else -> AppLinkParseResult.Rejected("unsupported")
    }
}

// ---------- generators (mirrored by Oracle141 GEN, seed 141003) ----------

private val AL_SEGMENT_POOL = arrayOf(
    "u", "chat", "post", "invite", "c", "join", "x", "", "alice", "a b", "a/b",
    "a!b", "a_b", "a-b", "a.b", "ABC123", "é😀", "a?b", "a#b", "a%20b", "%41",
    "a+b", "chat", "u", "  padded  ", "a/b/c", "0", "-1",
)
private val AL_QUERY_KEY_POOL = arrayOf("messageId", "comment", "other", "", "messageid", "MessageId")
private val AL_QUERY_VALUE_POOL = arrayOf(
    "m1", "a+b", "%6D%31", "%41", "%zz", "%4", "100%", "", "a b", "a/b",
    "x".repeat(65), "x".repeat(129), "a!b", "é", "a&b", "a=b",
)

private fun randomAlSegment(rnd: Random): String =
    if (rnd.nextInt(3) == 0) AL_SEGMENT_POOL[rnd.nextInt(AL_SEGMENT_POOL.size)] else {
        val len = rnd.nextInt(0, 9)
        buildString { repeat(len) { append("abcXYZ019_-./?#%+ ".get(rnd.nextInt(18))) } }
    }

private fun genAlQuery(rnd: Random): String {
    val n = rnd.nextInt(0, 4)
    return (0 until n).joinToString("&") {
        val k = AL_QUERY_KEY_POOL[rnd.nextInt(AL_QUERY_KEY_POOL.size)]
        val v = AL_QUERY_VALUE_POOL[rnd.nextInt(AL_QUERY_VALUE_POOL.size)]
        when (rnd.nextInt(3)) {
            0 -> "$k=$v"
            1 -> k
            else -> "=$v"
        }
    }
}

private fun genAlMaodou(rnd: Random): String {
    val n = rnd.nextInt(0, 4)
    val path = (0 until n).joinToString("/") { randomAlSegment(rnd) }
    return if (rnd.nextBoolean()) "$path?${genAlQuery(rnd)}" else path
}

private fun genAlHttps(rnd: Random): String {
    val n = rnd.nextInt(0, 4)
    val path = (0 until n).joinToString("/") { randomAlSegment(rnd) }
    return if (rnd.nextBoolean()) "$path?${genAlQuery(rnd)}" else path
}

private fun genAlQueryCase(rnd: Random): Pair<String, String> =
    Pair(genAlQuery(rnd), AL_QUERY_KEY_POOL[rnd.nextInt(AL_QUERY_KEY_POOL.size)])

class AppLinkSchemeParsingFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val maodou = listOf(
            "",
            "/",
            "u",
            "u/alice",
            "u/alice/bob",
            "u/a!b",
            "u/" + "x".repeat(65),
            "chat/abc",
            "chat/abc?messageId=m1",
            "chat/abc?messageId=",
            "chat/abc?messageId=%6D%31",
            "chat/abc?messageId=a+b",
            "chat/abc?other=x",
            "chat/abc?messageId=a&messageId=b",
            "chat/a/b",
            "chat/",
            "chat/a%20b",
            "chat/a?b",
            "chat/" + "x".repeat(129),
            "post/p1?comment=c1",
            "post/p1?comment=",
            "post/p1?comment=%41",
            "invite/CODE123",
            "invite/a/b",
            "invite/a b",
            "invite/",
            "unknown/x",
            "u/a b",
            "chat/abc?messageId=a%20b",
        )
        for (input in maodou) {
            assertEquals(
                parseMaodouSchemeOracle(input),
                with(AppLinkRouter) { parseMaodouScheme(input) },
                "maodou input=$input",
            )
        }
        val https = listOf(
            "",
            "/",
            "u/alice",
            "u/a!b",
            "u/",
            "u/alice/bob",
            "c/abc",
            "c/abc?messageId=m1",
            "c/a/b",
            "c/",
            "join/CODE",
            "join/a b",
            "join/",
            "join/a/b",
            "x/y",
            "u/" + "x".repeat(65),
            "c/" + "x".repeat(129),
        )
        for (input in https) {
            assertEquals(
                parseHttpsChatMdouOracle(input),
                with(AppLinkRouter) { parseHttpsChatMdou(input) },
                "https input=$input",
            )
        }
        val queries = listOf(
            Pair("a=1&b=2", "b"),
            Pair("a=1&b=2", "c"),
            Pair("", "a"),
            Pair("a=", "a"),
            Pair("a=%41", "a"),
            Pair("a=a+b", "a"),
            Pair("a=%zz", "a"),
            Pair("a=%4", "a"),
            Pair("a=100%", "a"),
            Pair("=1", ""),
            Pair("a=1&a=2", "a"),
            Pair("A=1", "a"),
            Pair("messageId=m1&comment=c1", "comment"),
            Pair("messageId=%6D%31", "messageId"),
            Pair("a", "a"),
            Pair("a=1&", "a"),
        )
        for ((query, key) in queries) {
            assertEquals(
                parseQueryParamOracle(query, key),
                with(AppLinkRouter) { parseQueryParam(query, key) },
                "query=$query key=$key",
            )
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(141003)
        repeat(1500) { i ->
            val m = genAlMaodou(rnd)
            assertEquals(
                parseMaodouSchemeOracle(m),
                with(AppLinkRouter) { parseMaodouScheme(m) },
                "maodou iter=$i input=$m",
            )
            val h = genAlHttps(rnd)
            assertEquals(
                parseHttpsChatMdouOracle(h),
                with(AppLinkRouter) { parseHttpsChatMdou(h) },
                "https iter=$i input=$h",
            )
            val (q, k) = genAlQueryCase(rnd)
            assertEquals(
                parseQueryParamOracle(q, k),
                with(AppLinkRouter) { parseQueryParam(q, k) },
                "query iter=$i query=$q key=$k",
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
        val maodouBases = listOf(
            "u/alice", "chat/abc?messageId=m1", "post/p1?comment=c1",
            "invite/CODE", "u", "chat", "messageId", "%41", "a+b",
        )
        for (base in maodouBases) {
            for (input in editsOf(base)) {
                assertEquals(
                    parseMaodouSchemeOracle(input),
                    with(AppLinkRouter) { parseMaodouScheme(input) },
                    "maodou sweep input=$input",
                )
            }
        }
        for (base in listOf("u/alice", "c/abc", "join/CODE")) {
            for (input in editsOf(base)) {
                assertEquals(
                    parseHttpsChatMdouOracle(input),
                    with(AppLinkRouter) { parseHttpsChatMdou(input) },
                    "https sweep input=$input",
                )
            }
        }
        for (code in 0x00..0x7F) {
            val input = String(Character.toChars(code))
            assertEquals(
                parseMaodouSchemeOracle(input),
                with(AppLinkRouter) { parseMaodouScheme(input) },
                "single-unit maodou code=$code",
            )
            assertEquals(
                parseQueryParamOracle(input, "a"),
                with(AppLinkRouter) { parseQueryParam(input, "a") },
                "single-unit query code=$code",
            )
        }
    }
}
