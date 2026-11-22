package com.maodouchat.core.session

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [ScreenSecurePolicy] (checklist line 931, 37th block).
 * Test-only, zero production changes.
 *
 * The oracle re-derives the window gate, route-head sets, chat-id extraction,
 * placeholder rules and pattern filling from the documented rules. Three
 * production quirks are reproduced verbatim, not "fixed": `onChatSurface`
 * does not influence [ScreenSecurePolicy.shouldSecureWindow]; `+` inside a
 * route segment stays a literal plus (it is rewritten to `%2B` before
 * decoding); and a malformed `%` escape (or a decoded blank) yields no
 * chat id instead of throwing. A Python mini-model differentially checks
 * both oracles on 5000 random inputs per group (0 diffs) before this file
 * was written; the JDK's own `URLDecoder`/`URI` behaviour was probed
 * directly to pin the `%`-decoding edges.
 */
class ScreenSecurePolicyFuzzTest {

    private val chatHeads = listOf(
        "chat_detail", "chat_detail_two_pane", "chat_detail_list_pane",
        "group_detail", "starred_messages", "ai_tasks", "media_center",
        "watermark_forensic", "call", "incoming_call",
        "group_poll", "group_checkin", "group_chain", "group_pk"
    )
    private val pathPrefixes = listOf(
        "chat_detail", "chat_detail_two_pane",
        "group_detail", "starred_messages", "ai_tasks", "media_center",
        "group_poll", "group_checkin", "group_chain", "group_pk"
    )
    private val optimisticHeads = pathPrefixes
    private val otherHeads = listOf(
        "main", "settings", "call_history", "chat", "group", "starred", "ai",
        "media", "CHAT_DETAIL", "Chat_Detail", "chat_detailx", "xchat_detail",
        "chat_detail_list", "group_detailx", ""
    )

    private fun oracleShouldSecureWindow(
        appLockShowing: Boolean,
        globalEnabled: Boolean,
        @Suppress("UNUSED_PARAMETER") onChatSurface: Boolean,
        secretChatSurfaceActive: Boolean,
        chatLockSurfaceActive: Boolean
    ): Boolean = appLockShowing || secretChatSurfaceActive || chatLockSurfaceActive || globalEnabled

    private fun oracleIsChatSurfaceRoute(route: String?): Boolean {
        if (route.isNullOrBlank()) return false
        val head = route.substringBefore('?').substringBefore('/')
        return head in chatHeads
    }

    private fun oracleIsOptimisticSecretSurface(route: String?): Boolean {
        if (route.isNullOrBlank()) return false
        val head = route.substringBefore('?').substringBefore('/')
        return head in optimisticHeads
    }

    private fun oracleIsNavPlaceholder(chatId: String?): Boolean {
        if (chatId.isNullOrBlank()) return false
        return chatId.startsWith("{") && chatId.endsWith("}")
    }

    private fun oracleTakeRealChatId(chatId: String?): String? =
        chatId?.takeIf { it.isNotBlank() && !oracleIsNavPlaceholder(it) }

    /** Mirrors `URLDecoder.decode(raw.replace("+", "%2B"), UTF-8)` with runCatching. */
    private fun oracleDecodeSegment(raw: String): String? {
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%') {
                if (i + 2 >= raw.length) return null
                val high = raw[i + 1].digitToIntOrNull(16) ?: return null
                val low = raw[i + 2].digitToIntOrNull(16) ?: return null
                bytes.add(((high shl 4) + low).toByte())
                i += 3
            } else {
                for (b in c.toString().toByteArray(Charsets.UTF_8)) bytes.add(b)
                i++
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8).takeIf { it.isNotBlank() }
    }

    private fun oracleQueryParam(query: String, name: String): String? {
        if (query.isBlank()) return null
        return query.split('&').firstNotNullOfOrNull { part ->
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', missingDelimiterValue = "")
            value.takeIf { key == name && it.isNotBlank() }
        }
    }

    private fun oracleExtractChatIdFromRoute(route: String?): String? {
        if (route.isNullOrBlank()) return null
        val path = route.substringBefore('?')
        val query = route.substringAfter('?', missingDelimiterValue = "")
        val segments = path.split('/').filter { it.isNotBlank() }
        val prefix = segments.getOrNull(0)
        val pathChatId = if (prefix != null && prefix in pathPrefixes && segments.size >= 2) {
            oracleDecodeSegment(segments[1])
        } else {
            null
        }
        if (!pathChatId.isNullOrBlank()) return oracleTakeRealChatId(pathChatId)
        return oracleTakeRealChatId(oracleQueryParam(query, "chatId")?.let { oracleDecodeSegment(it) })
    }

    private fun oracleResolveChatId(
        argumentChatId: String?,
        filledRoute: String?,
        routePattern: String?
    ): String? {
        val fromArgs = oracleTakeRealChatId(argumentChatId)
        if (fromArgs != null) return fromArgs
        return oracleExtractChatIdFromRoute(filledRoute)
            ?: oracleExtractChatIdFromRoute(routePattern)
    }

    private val placeholderRegex = Regex("\\{([^}]+)\\}")

    private fun oracleFillRoutePattern(pattern: String?, arguments: Map<String, String?>): String? {
        if (pattern.isNullOrBlank()) return pattern
        return placeholderRegex.replace(pattern) { match ->
            val key = match.groupValues[1]
            val value = arguments[key]
            if (value.isNullOrBlank()) match.value else value
        }
    }

    private fun randomSegment(rnd: Random): String {
        val pool = listOf(
            "abc", "c1", "g1", "u1", "123", "a b", "a+b", "a%2Bb", "x%20y",
            "%20", "%ZZ", "%2", "%", "%FF", "%E4%B8%AD", "%C3%28", "%u1234",
            "a%00b", "{chatId}", "{messageId}", "{}", "{", "}", "", "  ",
            "user-1", "Alice", "a/b"
        )
        return when (rnd.nextInt(5)) {
            0 -> pool.random(rnd)
            1 -> "id" + rnd.nextInt(1000)
            2 -> List(rnd.nextInt(1, 6)) { ('a' + rnd.nextInt(26)) }.joinToString("")
            3 -> "%" + "%02X".format(rnd.nextInt(256))
            else -> pool.random(rnd) + pool.random(rnd)
        }
    }

    private fun randomQuery(rnd: Random): String {
        val pool = listOf(
            "", "chatId=c1", "chatId=", "chatId=%20", "chatId={chatId}",
            "foo=1&chatId=c9", "chatId=a+b", "chatId=a%2Bb", "chatid=c1",
            "chatId=c1&chatId=c2", "chatId=%ZZ", "x=1", "chatId=%E4%B8%AD",
            "chatId=%FF", "chatId=%", "chatId=%2", "chatId=%u1234",
            "messageId=m1", "chatId=a%00b", "chatId=  ", "chatId=%C3%28"
        )
        return pool.random(rnd)
    }

    private fun randomRoute(rnd: Random): String? {
        val heads = chatHeads + otherHeads
        return when (rnd.nextInt(10)) {
            0 -> null
            1 -> ""
            2 -> "   "
            3 -> heads.random(rnd)
            4 -> heads.random(rnd) + "/" + randomSegment(rnd)
            5 -> heads.random(rnd) + "?" + randomQuery(rnd)
            6 -> heads.random(rnd) + "/" + randomSegment(rnd) + "?" + randomQuery(rnd)
            7 -> heads.random(rnd) + "/" + randomSegment(rnd) + "/" + randomSegment(rnd)
            8 -> "settings/account_security"
            else -> heads.random(rnd) + "/" + randomSegment(rnd) + "?messageId=m1"
        }
    }

    private fun randomChatId(rnd: Random): String? {
        val pool = listOf(
            null, "", " ", "  ", "abc", "real-id", "{chatId}", "{}", "{",
            "}", "{x", "x}", "{{x}}", "{chatId", "chatId}", "a{b}c",
            "{messageId}", "c1", "user-9"
        )
        return pool.random(rnd)
    }

    private fun randomPattern(rnd: Random): String? {
        val pool = listOf(
            null, "", "   ", "chat_detail/{chatId}",
            "chat_detail/{chatId}?messageId={messageId}",
            "chat_detail/{chatId}/{messageId}",
            "group_poll/{chatId}/vote/{optionId}",
            "chat_detail/abc", "{a}{b}", "{{x}}", "{chatId",
            "chatId}", "chat_detail/{chatId}?x={missing}",
            "media_center/{id}?tab={tab}"
        )
        return pool.random(rnd)
    }

    private fun randomArguments(rnd: Random): Map<String, String?> {
        val keys = listOf("chatId", "messageId", "optionId", "id", "tab", "{x", "missing")
        val values = listOf<String?>("abc", "m1", "c9", "a/b", "", "  ", null, "x y", "v")
        val map = LinkedHashMap<String, String?>()
        repeat(rnd.nextInt(0, 5)) {
            map[keys.random(rnd)] = values.random(rnd)
        }
        return map
    }

    @Test
    fun shouldSecureWindowRandomOracle() {
        val rnd = Random(37)
        repeat(300) {
            val appLock = rnd.nextBoolean()
            val global = rnd.nextBoolean()
            val onChat = rnd.nextBoolean()
            val secret = rnd.nextBoolean()
            val chatLock = rnd.nextBoolean()
            assertEquals(
                oracleShouldSecureWindow(appLock, global, onChat, secret, chatLock),
                ScreenSecurePolicy.shouldSecureWindow(appLock, global, onChat, secret, chatLock),
                "appLock=<$appLock> global=<$global> onChat=<$onChat> secret=<$secret> chatLock=<$chatLock>"
            )
        }
    }

    @Test
    fun chatSurfaceAndOptimisticRandomOracle() {
        val rnd = Random(37)
        repeat(300) {
            val route = randomRoute(rnd)
            assertEquals(
                oracleIsChatSurfaceRoute(route),
                ScreenSecurePolicy.isChatSurfaceRoute(route),
                "route=<$route>"
            )
            assertEquals(
                oracleIsOptimisticSecretSurface(route),
                ScreenSecurePolicy.isOptimisticSecretSurface(route),
                "route=<$route>"
            )
        }
    }

    @Test
    fun extractChatIdRandomOracle() {
        val rnd = Random(37)
        repeat(300) {
            val route = randomRoute(rnd)
            assertEquals(
                oracleExtractChatIdFromRoute(route),
                ScreenSecurePolicy.extractChatIdFromRoute(route),
                "route=<$route>"
            )
        }
    }

    @Test
    fun placeholderAndTakeRealRandomOracle() {
        val rnd = Random(37)
        repeat(300) {
            val chatId = randomChatId(rnd)
            assertEquals(
                oracleIsNavPlaceholder(chatId),
                ScreenSecurePolicy.isNavPlaceholder(chatId),
                "chatId=<$chatId>"
            )
            assertEquals(
                oracleTakeRealChatId(chatId),
                ScreenSecurePolicy.takeRealChatId(chatId),
                "chatId=<$chatId>"
            )
        }
    }

    @Test
    fun resolveChatIdRandomOracle() {
        val rnd = Random(37)
        repeat(300) {
            val arg = randomChatId(rnd)
            val filled = randomRoute(rnd)
            val pattern = randomPattern(rnd)
            assertEquals(
                oracleResolveChatId(arg, filled, pattern),
                ScreenSecurePolicy.resolveChatId(arg, filled, pattern),
                "arg=<$arg> filled=<$filled> pattern=<$pattern>"
            )
        }
    }

    @Test
    fun fillRoutePatternRandomOracle() {
        val rnd = Random(37)
        repeat(300) {
            val pattern = randomPattern(rnd)
            val args = randomArguments(rnd)
            assertEquals(
                oracleFillRoutePattern(pattern, args),
                ScreenSecurePolicy.fillRoutePattern(pattern, args),
                "pattern=<$pattern> args=<$args>"
            )
        }
    }

    @Test
    fun pinnedEdges() {
        // `+` stays a literal plus; `%2B` decodes to plus.
        assertEquals("a+b", ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/a+b"))
        assertEquals("a+b", ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/a%2Bb"))
        // Percent decoding: space, CJK, replacement char, malformed escapes.
        assertEquals("x y", ScreenSecurePolicy.extractChatIdFromRoute("media_center/x%20y"))
        assertEquals("\u4e2d", ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/%E4%B8%AD"))
        assertEquals("\uFFFD", ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/%FF"))
        assertEquals("\uFFFD(", ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/%C3%28"))
        assertEquals("a\u0000b", ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/a%00b"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/%ZZ"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/%2"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/%"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/100%"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/%u1234"))
        // Blank segments are filtered; a blank decoded id yields nothing.
        assertEquals("abc", ScreenSecurePolicy.extractChatIdFromRoute("chat_detail//abc"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/ "))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("starred_messages?chatId=%20"))
        // Query param: first match wins, key is case sensitive.
        assertEquals("c1", ScreenSecurePolicy.extractChatIdFromRoute("starred_messages?chatId=c1&chatId=c2"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("starred_messages?chatid=c1"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("starred_messages?chatId=%u1234"))
        // Placeholders never count as real ids.
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/{chatId}"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/{}"))
        assertNull(ScreenSecurePolicy.extractChatIdFromRoute("chat_detail/{x}"))
        assertTrue(ScreenSecurePolicy.isNavPlaceholder("{}"))
        assertFalse(ScreenSecurePolicy.isNavPlaceholder("{"))
        assertFalse(ScreenSecurePolicy.isNavPlaceholder("}"))
        // resolveChatId falls through blank args and pattern-only routes.
        assertNull(ScreenSecurePolicy.resolveChatId(null, null, null))
        assertEquals("abc", ScreenSecurePolicy.resolveChatId("  ", "chat_detail/abc", null))
        assertNull(ScreenSecurePolicy.resolveChatId(null, "chat_detail/{chatId}", "chat_detail/{chatId}"))
        // fillRoutePattern: values are inserted literally; missing values keep the token.
        assertEquals("chat_detail/a/b", ScreenSecurePolicy.fillRoutePattern("chat_detail/{chatId}", mapOf("chatId" to "a/b")))
        assertEquals("v}", ScreenSecurePolicy.fillRoutePattern("{{x}}", mapOf("{x" to "v")))
        assertEquals("12", ScreenSecurePolicy.fillRoutePattern("{a}{b}", mapOf("a" to "1", "b" to "2")))
        assertNull(ScreenSecurePolicy.fillRoutePattern(null, mapOf("chatId" to "abc")))
        assertEquals("  ", ScreenSecurePolicy.fillRoutePattern("  ", mapOf("chatId" to "abc")))
        assertEquals("", ScreenSecurePolicy.fillRoutePattern("", mapOf("chatId" to "abc")))
        // onChatSurface alone never secures the window.
        assertFalse(ScreenSecurePolicy.shouldSecureWindow(false, false, true, false, false))
    }
}
