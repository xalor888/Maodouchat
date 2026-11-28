package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * AppLinkRouter.parseDeepLink / resolveUserFacingUrl 的随机 oracle fuzz。
 * oracle 按生产源码逐字转录；随机输入固定种子，可复现。
 * 安全语义：任意输入永不抛异常；Accepted 当且仅当白名单 scheme/host 形态。
 */
class AppLinkRouterFuzzTest {

    private val random = Random(2026112201)

    // ---- 清洗器 oracle（按 AppLinkSanitizers.kt 逐字转录） ----

    private fun expectedSanitizeUsername(raw: String): String? {
        val single = raw.substringBefore('/').substringBefore('?').substringBefore('#').trim()
            .take(AppLinkRouter.MAX_USERNAME_LENGTH)
        if (single.isBlank()) return null
        if (single.any { c -> !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.') }) return null
        return single
    }

    private fun expectedSanitizeInviteCode(raw: String): String? {
        val single = raw.substringBefore('/').substringBefore('?').substringBefore('#').trim()
            .take(AppLinkRouter.MAX_INVITE_CODE_LENGTH)
        if (single.isBlank()) return null
        if (single.any { c -> !(c.isLetterOrDigit() || c == '_' || c == '-') }) return null
        return single
    }

    private fun expectedSanitizeOpaqueId(raw: String): String? {
        val single = raw.substringBefore('/').substringBefore('?').substringBefore('#').trim()
            .take(AppLinkRouter.MAX_ID_LENGTH)
        if (single.isBlank()) return null
        if (single.any { c -> c.isWhitespace() || c.code < 0x20 }) return null
        return single
    }

    private fun expectedSanitizeChatInviteToken(raw: String): String? {
        val single = raw.trim()
        if (single.any { it == '/' || it == '?' || it == '#' }) return null
        if (single.length !in AppLinkRouter.MIN_CHAT_INVITE_TOKEN_LENGTH..AppLinkRouter.MAX_INVITE_CODE_LENGTH) return null
        if (single.any { c -> !(c.isLetterOrDigit() || c == '_' || c == '-') }) return null
        return single
    }

    private fun expectedSanitizeHttpUrl(raw: String): String? {
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

    // ---- 编解码 oracle（按 AppLinkEncoding.kt 逐字转录） ----

    private fun expectedDecodeComponent(raw: String): String {
        val hex = "0123456789ABCDEF"
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%' && i + 2 < raw.length) {
                val hi = hex.indexOf(raw[i + 1].uppercaseChar())
                val lo = hex.indexOf(raw[i + 2].uppercaseChar())
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

    private fun expectedParseQueryParam(query: String, key: String): String? {
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq) == key) {
                return expectedDecodeComponent(pair.substring(eq + 1)).takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    // ---- 路由 oracle（按 AppLinkParsing.kt / AppLinkSchemeParsing.kt 逐字转录） ----

    private fun expectedParseMaodouScheme(afterScheme: String): AppLinkParseResult {
        val path = afterScheme.substringBefore('?')
        val query = afterScheme.substringAfter('?', "")
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return AppLinkParseResult.Rejected("empty_path")
        return when (segments[0]) {
            "u" -> {
                val username = expectedSanitizeUsername(segments.drop(1).firstOrNull().orEmpty())
                    ?: return AppLinkParseResult.Rejected("bad_username")
                if (segments.size > 2) return AppLinkParseResult.Rejected("bad_username")
                AppLinkParseResult.Accepted(AppLinkDestination.PublicProfile(username))
            }
            "chat" -> {
                val chatId = expectedSanitizeOpaqueId(segments.drop(1).firstOrNull().orEmpty())
                    ?: return AppLinkParseResult.Rejected("bad_chat")
                if (segments.size > 2) return AppLinkParseResult.Rejected("bad_chat")
                val messageId = expectedParseQueryParam(query, "messageId")?.let {
                    expectedSanitizeOpaqueId(it) ?: return AppLinkParseResult.Rejected("bad_message")
                }
                AppLinkParseResult.Accepted(AppLinkDestination.ChatDetail(chatId, messageId))
            }
            "post" -> {
                val postId = expectedSanitizeOpaqueId(segments.drop(1).firstOrNull().orEmpty())
                    ?: return AppLinkParseResult.Rejected("bad_post")
                if (segments.size > 2) return AppLinkParseResult.Rejected("bad_post")
                val comment = expectedParseQueryParam(query, "comment")?.let {
                    expectedSanitizeOpaqueId(it) ?: return AppLinkParseResult.Rejected("bad_comment")
                }
                AppLinkParseResult.Accepted(AppLinkDestination.PostDetail(postId, comment))
            }
            "invite" -> {
                val code = expectedSanitizeInviteCode(segments.drop(1).firstOrNull().orEmpty())
                    ?: return AppLinkParseResult.Rejected("bad_invite")
                if (segments.size > 2) return AppLinkParseResult.Rejected("bad_invite")
                AppLinkParseResult.Accepted(AppLinkDestination.GroupInvite(code))
            }
            else -> AppLinkParseResult.Rejected("unsupported")
        }
    }

    private fun expectedParseHttpsChatMdou(pathAndQuery: String): AppLinkParseResult {
        val path = pathAndQuery.substringBefore('?')
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return AppLinkParseResult.Rejected("empty_path")
        return when (segments[0]) {
            "u" -> {
                val username = expectedSanitizeUsername(segments.drop(1).firstOrNull().orEmpty())
                    ?: return AppLinkParseResult.Rejected("bad_username")
                if (segments.size > 2) return AppLinkParseResult.Rejected("bad_username")
                AppLinkParseResult.Accepted(AppLinkDestination.PublicProfile(username))
            }
            "c" -> {
                val chatId = expectedSanitizeOpaqueId(segments.drop(1).firstOrNull().orEmpty())
                    ?: return AppLinkParseResult.Rejected("bad_chat")
                if (segments.size > 2) return AppLinkParseResult.Rejected("bad_chat")
                AppLinkParseResult.Accepted(AppLinkDestination.ChatDetail(chatId))
            }
            "join" -> {
                val code = expectedSanitizeInviteCode(segments.drop(1).firstOrNull().orEmpty())
                    ?: return AppLinkParseResult.Rejected("bad_invite")
                if (segments.size > 2) return AppLinkParseResult.Rejected("bad_invite")
                AppLinkParseResult.Accepted(AppLinkDestination.GroupInvite(code))
            }
            else -> AppLinkParseResult.Rejected("unsupported")
        }
    }

    private fun expectedParseDeepLink(uriString: String): AppLinkParseResult {
        val raw = uriString.trim()
        if (raw.isEmpty()) return AppLinkParseResult.Rejected("empty")
        if (raw.startsWith(AppLinkRouter.CHAT_INVITE_COLON_PREFIX, ignoreCase = true)) {
            val token = expectedSanitizeChatInviteToken(
                raw.substring(AppLinkRouter.CHAT_INVITE_COLON_PREFIX.length)
            ) ?: return AppLinkParseResult.Rejected("bad_invite")
            return AppLinkParseResult.Accepted(AppLinkDestination.GroupInvite(token))
        }
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd <= 0) return AppLinkParseResult.Rejected("unsupported")
        val scheme = raw.substring(0, schemeEnd).lowercase()
        val afterScheme = raw.substring(schemeEnd + 3)
        return when {
            scheme == "maodouchat" -> expectedParseMaodouScheme(afterScheme)
            scheme == "https" && afterScheme.substringBefore('/').lowercase() == "chat.mdou.me" &&
                afterScheme.contains('/') ->
                expectedParseHttpsChatMdou(afterScheme.substringAfter('/'))
            else -> AppLinkParseResult.Rejected("unsupported")
        }
    }

    private fun expectedResolveUserFacingUrl(raw: String): AppLinkParseResult {
        when (val deep = expectedParseDeepLink(raw)) {
            is AppLinkParseResult.Accepted -> return deep
            is AppLinkParseResult.Rejected -> Unit
        }
        val http = expectedSanitizeHttpUrl(raw) ?: return AppLinkParseResult.Rejected("unsupported")
        return AppLinkParseResult.Accepted(AppLinkDestination.ExternalUrl(http))
    }

    // ---- 随机输入 ----

    private fun randomUri(): String {
        if (random.nextInt(4) == 0) {
            val alphabet = "abcXYZ019:/.?&=#%+_-. \téüλ中"
            return buildString {
                repeat(random.nextInt(0, 41)) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
        }
        val scheme = listOf(
            "maodouchat", "MAODOUCHAT", "Maodouchat", "https", "HTTPS", "http",
            "javascript", "file", "intent", "ftp", "",
            "maodouchat:chat-invite:v1:", "maodouchat:chat-invite:V1:", "chat.mdou.me"
        ).random(random)
        val sep = listOf("://", ":", ":/", "//", "", "/").random(random)
        val rest = listOf(
            "u/alice", "u/a!b", "chat/abc?messageId=m1", "chat/abc?messageId=",
            "post/p1?comment=c2", "invite/code_1", "c/abc", "join/code", "u/", "", "u",
            "chat", "u/alice/bob", "x/y", "u/a b", "u/" + "a".repeat(70),
            "chat/" + "c".repeat(130), "invite/" + "t".repeat(85),
            "a".repeat(40), "a".repeat(31), "a".repeat(81), "a/b?c=d", "%41+%2",
            "chat.mdou.me/u/bob", "chat.mdou.me.evil.com/u/bob", "chat.mdou.me:443/u/bob"
        ).random(random)
        val tail = listOf("", " ", "?x=1", "#frag", "%20", " extra").random(random)
        return scheme + sep + rest + tail
    }

    @Test
    fun parseDeepLinkMatchesTranscribedOracle() {
        repeat(300) {
            val input = randomUri()
            assertEquals(expectedParseDeepLink(input), AppLinkRouter.parseDeepLink(input))
        }
    }

    @Test
    fun resolveUserFacingUrlMatchesOracle() {
        repeat(300) {
            val input = randomUri()
            assertEquals(expectedResolveUserFacingUrl(input), AppLinkRouter.resolveUserFacingUrl(input))
        }
    }

    @Test
    fun nearWhitelistHostsAndSchemesPinned() {
        // 近似白名单 host 一律拒绝：子域名后缀、@ 混淆、端口后缀都不是 chat.mdou.me。
        assertIs<AppLinkParseResult.Rejected>(
            AppLinkRouter.parseDeepLink("https://chat.mdou.me.evil.com/u/alice")
        )
        assertIs<AppLinkParseResult.Rejected>(
            AppLinkRouter.parseDeepLink("https://chat.mdou.me@evil.com/u/alice")
        )
        assertIs<AppLinkParseResult.Rejected>(
            AppLinkRouter.parseDeepLink("https://chat.mdou.me:443/u/alice")
        )
        assertIs<AppLinkParseResult.Rejected>(AppLinkRouter.parseDeepLink("https://chat.mdou.me"))
        assertEquals(
            AppLinkParseResult.Rejected("empty_path"),
            AppLinkRouter.parseDeepLink("https://chat.mdou.me/")
        )
        // scheme/host 大小写归一化后匹配白名单。
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.PublicProfile("alice")),
            AppLinkRouter.parseDeepLink("https://CHAT.MDOU.ME/u/alice")
        )
        assertIs<AppLinkParseResult.Accepted>(
            AppLinkRouter.parseDeepLink("HTTPS://chat.mdou.me/u/alice")
        )
        assertIs<AppLinkParseResult.Accepted>(AppLinkRouter.parseDeepLink("MAODOUCHAT://u/alice"))
        // 危险 scheme 一律拒绝。
        assertIs<AppLinkParseResult.Rejected>(AppLinkRouter.parseDeepLink("javascript:alert(1)"))
        assertIs<AppLinkParseResult.Rejected>(AppLinkRouter.parseDeepLink("file:///etc/passwd"))
        assertIs<AppLinkParseResult.Rejected>(AppLinkRouter.parseDeepLink("intent://x#Intent;end"))
        // 冒号邀请协议：token 32–80 位，大小写不敏感的前缀。
        assertEquals(
            AppLinkParseResult.Rejected("bad_invite"),
            AppLinkRouter.parseDeepLink("maodouchat:chat-invite:v1:abc")
        )
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.GroupInvite("a".repeat(32))),
            AppLinkRouter.parseDeepLink("maodouchat:chat-invite:v1:" + "a".repeat(32))
        )
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.GroupInvite("B".repeat(32))),
            AppLinkRouter.parseDeepLink("MAODOUCHAT:CHAT-INVITE:V1:" + "B".repeat(32))
        )
        assertIs<AppLinkParseResult.Rejected>(
            AppLinkRouter.parseDeepLink("maodouchat:chat-invite:v1:" + "a".repeat(31))
        )
        assertIs<AppLinkParseResult.Rejected>(
            AppLinkRouter.parseDeepLink("maodouchat:chat-invite:v1:" + "a".repeat(81))
        )
        assertIs<AppLinkParseResult.Rejected>(
            AppLinkRouter.parseDeepLink("maodouchat:chat-invite:v1:" + "a".repeat(31) + "/")
        )
        // 冒号协议没有 ://：带 :// 的同形串走 scheme 分支，首段不是白名单路径。
        assertEquals(
            AppLinkParseResult.Rejected("unsupported"),
            AppLinkRouter.parseDeepLink("maodouchat://chat-invite:v1:" + "a".repeat(32))
        )
        // 用户面链接：白名单外 http(s) 走系统浏览器；其余拒绝。
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.ExternalUrl("https://example.com/page")),
            AppLinkRouter.resolveUserFacingUrl("https://example.com/page")
        )
        assertIs<AppLinkParseResult.Rejected>(
            AppLinkRouter.resolveUserFacingUrl("http://example.com/a b")
        )
        assertIs<AppLinkParseResult.Rejected>(
            AppLinkRouter.resolveUserFacingUrl("https://example.com/" + "x".repeat(3000))
        )
        assertIs<AppLinkParseResult.Rejected>(
            AppLinkRouter.resolveUserFacingUrl("javascript:alert(1)")
        )
        assertIs<AppLinkParseResult.Accepted>(
            AppLinkRouter.resolveUserFacingUrl("maodouchat://u/alice")
        )
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.ExternalUrl("https://example.com/x")),
            AppLinkRouter.resolveUserFacingUrl("  https://example.com/x  ")
        )
    }

    @Test
    fun sanitizerTruncationAndQueryDecodingPinned() {
        // 用户名 take(64)：超长截断接受，非法字符在截断后仍拒绝。
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.PublicProfile("a".repeat(64))),
            AppLinkRouter.parseDeepLink("maodouchat://u/" + "a".repeat(64))
        )
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.PublicProfile("a".repeat(64))),
            AppLinkRouter.parseDeepLink("maodouchat://u/" + "a".repeat(65))
        )
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.PublicProfile("a".repeat(64))),
            AppLinkRouter.parseDeepLink("maodouchat://u/" + "a".repeat(64) + "!")
        )
        assertEquals(
            AppLinkParseResult.Rejected("bad_username"),
            AppLinkRouter.parseDeepLink("maodouchat://u/!" + "a".repeat(64))
        )
        // opaque ID take(128)、邀请码 take(80) 同样截断接受。
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.ChatDetail("c".repeat(128), null)),
            AppLinkRouter.parseDeepLink("maodouchat://chat/" + "c".repeat(129))
        )
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.GroupInvite("k".repeat(80))),
            AppLinkRouter.parseDeepLink("maodouchat://invite/" + "k".repeat(81))
        )
        // 空路径与多余路径段。
        assertEquals(
            AppLinkParseResult.Rejected("bad_username"),
            AppLinkRouter.parseDeepLink("maodouchat://u/")
        )
        assertEquals(
            AppLinkParseResult.Rejected("empty_path"),
            AppLinkRouter.parseDeepLink("maodouchat://")
        )
        assertEquals(
            AppLinkParseResult.Rejected("bad_username"),
            AppLinkRouter.parseDeepLink("maodouchat://u")
        )
        assertEquals(
            AppLinkParseResult.Rejected("bad_username"),
            AppLinkRouter.parseDeepLink("maodouchat://u/alice/bob")
        )
        // 查询参数解码：% 需后跟两位 hex；+ 解为空格后被 opaque 清洗拒绝。
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.ChatDetail("c1", null)),
            AppLinkRouter.parseDeepLink("maodouchat://chat/c1?messageId=")
        )
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.ChatDetail("c1", "m4")),
            AppLinkRouter.parseDeepLink("maodouchat://chat/c1?messageId=m%34")
        )
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.ChatDetail("c1", "m%2")),
            AppLinkRouter.parseDeepLink("maodouchat://chat/c1?messageId=m%2")
        )
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.ChatDetail("c1", "m%zz")),
            AppLinkRouter.parseDeepLink("maodouchat://chat/c1?messageId=m%zz")
        )
        assertEquals(
            AppLinkParseResult.Rejected("bad_message"),
            AppLinkRouter.parseDeepLink("maodouchat://chat/c1?messageId=m+n")
        )
        // 参数名大小写敏感；首个同名参数获胜。
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.ChatDetail("c1", null)),
            AppLinkRouter.parseDeepLink("maodouchat://chat/c1?MESSAGEID=m1")
        )
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.ChatDetail("c1", "m2")),
            AppLinkRouter.parseDeepLink("maodouchat://chat/c1?x=1&messageId=m2")
        )
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.ChatDetail("c1", "m1")),
            AppLinkRouter.parseDeepLink("maodouchat://chat/c1?messageId=m1&messageId=m2")
        )
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.PostDetail("p1", "cA")),
            AppLinkRouter.parseDeepLink("maodouchat://post/p1?comment=c%41")
        )
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.PostDetail("p1", null)),
            AppLinkRouter.parseDeepLink("maodouchat://post/p1?comment=")
        )
        // https 形态的 c/join/u 路径与 maodouchat 形态同义。
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.ChatDetail("abc", null)),
            AppLinkRouter.parseDeepLink("https://chat.mdou.me/c/abc")
        )
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.GroupInvite("code-1")),
            AppLinkRouter.parseDeepLink("https://chat.mdou.me/join/code-1")
        )
        assertEquals(
            AppLinkParseResult.Rejected("unsupported"),
            AppLinkRouter.parseDeepLink("https://chat.mdou.me/x/y")
        )
        assertEquals(
            AppLinkParseResult.Rejected("bad_username"),
            AppLinkRouter.parseDeepLink("https://chat.mdou.me/u/a%20b")
        )
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.PublicProfile("alice")),
            AppLinkRouter.parseDeepLink("maodouchat://u/alice?x=1")
        )
    }

    @Test
    fun maodouAndHttpsSchemesAgreeOnSharedPaths() {
        val alphabet = "abcXYZ019_-."
        repeat(200) {
            val id = buildString {
                repeat(random.nextInt(0, 12)) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            assertEquals(
                AppLinkRouter.parseDeepLink("maodouchat://u/$id"),
                AppLinkRouter.parseDeepLink("https://chat.mdou.me/u/$id")
            )
            assertEquals(
                AppLinkRouter.parseDeepLink("maodouchat://invite/$id"),
                AppLinkRouter.parseDeepLink("https://chat.mdou.me/join/$id")
            )
            assertEquals(
                AppLinkRouter.parseDeepLink("maodouchat://chat/$id"),
                AppLinkRouter.parseDeepLink("https://chat.mdou.me/c/$id")
            )
        }
    }

    @Test
    fun emptyAndWhitespaceInputsPinned() {
        assertEquals(
            AppLinkParseResult.Rejected("empty"),
            AppLinkRouter.parseDeepLink("")
        )
        assertEquals(
            AppLinkParseResult.Rejected("empty"),
            AppLinkRouter.parseDeepLink(" ")
        )
        assertEquals(
            AppLinkParseResult.Rejected("empty"),
            AppLinkRouter.parseDeepLink("\t\n")
        )
        // 首尾空白先 trim：深链仍可接受。
        assertEquals(
            AppLinkParseResult.Accepted(AppLinkDestination.PublicProfile("a")),
            AppLinkRouter.parseDeepLink("   maodouchat://u/a   ")
        )
        // 中间空白不属于 trim：用户名清洗拒绝。
        assertEquals(
            AppLinkParseResult.Rejected("bad_username"),
            AppLinkRouter.parseDeepLink("maodouchat://u/a b")
        )
        assertEquals(
            AppLinkParseResult.Rejected("unsupported"),
            AppLinkRouter.parseDeepLink("://u/alice")
        )
        assertEquals(
            AppLinkParseResult.Rejected("unsupported"),
            AppLinkRouter.parseDeepLink("not-a-uri")
        )
    }
}
