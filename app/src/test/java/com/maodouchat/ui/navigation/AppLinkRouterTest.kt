package com.maodouchat.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppLinkRouterTest {

    @Test
    fun maodouUserLinkAccepted() {
        val res = AppLinkRouter.parseDeepLink("maodouchat://u/alice_01")
        assertIs<AppLinkParseResult.Accepted>(res)
        val dest = res.destination as AppLinkDestination.PublicProfile
        assertEquals("alice_01", dest.username)
        assertTrue(dest.requiresAuth)
        assertEquals("public_profile/alice_01", dest.toRoute())
    }

    @Test
    fun httpsUserLinkWithEmbedAccepted() {
        val res = AppLinkRouter.parseDeepLink("https://chat.mdou.me/u/bob?embed=1")
        assertIs<AppLinkParseResult.Accepted>(res)
        assertEquals("bob", (res.destination as AppLinkDestination.PublicProfile).username)
    }

    @Test
    fun traversalUsernameRejected() {
        // 与 MainActivity 旧旁路一致：多余路径段必须拒绝，而非静默取首段。
        val res = AppLinkRouter.parseDeepLink("maodouchat://u/alice/bob")
        assertIs<AppLinkParseResult.Rejected>(res)
    }

    @Test
    fun illegalUsernameCharsRejected() {
        assertIs<AppLinkParseResult.Rejected>(AppLinkRouter.parseDeepLink("maodouchat://u/a!b"))
        assertIs<AppLinkParseResult.Rejected>(AppLinkRouter.parseDeepLink("https://chat.mdou.me/u/a%20b"))
    }

    @Test
    fun dangerousSchemesRejected() {
        assertIs<AppLinkParseResult.Rejected>(AppLinkRouter.parseDeepLink("javascript:alert(1)"))
        assertIs<AppLinkParseResult.Rejected>(AppLinkRouter.parseDeepLink("file:///etc/passwd"))
        assertIs<AppLinkParseResult.Rejected>(AppLinkRouter.parseDeepLink(""))
        assertIs<AppLinkParseResult.Rejected>(AppLinkRouter.parseDeepLink("not-a-uri"))
    }

    @Test
    fun untrustedHostRejected() {
        assertIs<AppLinkParseResult.Rejected>(AppLinkRouter.parseDeepLink("https://evil.com/u/alice"))
    }

    @Test
    fun chatLinkRoundTrip() {
        val res = AppLinkRouter.parseDeepLink("maodouchat://chat/abc-123?messageId=msg-9")
        assertIs<AppLinkParseResult.Accepted>(res)
        val dest = res.destination as AppLinkDestination.ChatDetail
        assertEquals("abc-123", dest.chatId)
        assertEquals("msg-9", dest.messageId)
        assertEquals("chat_detail/abc-123?messageId=msg-9", dest.toRoute())
    }

    @Test
    fun inviteLinksAccepted() {
        val a = AppLinkRouter.parseDeepLink("maodouchat://invite/ABCdef-12_")
        assertIs<AppLinkParseResult.Accepted>(a)
        assertEquals("ABCdef-12_", (a.destination as AppLinkDestination.GroupInvite).code)
        val b = AppLinkRouter.parseDeepLink("https://chat.mdou.me/join/XYZ-9")
        assertIs<AppLinkParseResult.Accepted>(b)
    }

    @Test
    fun overlongUsernameTruncatedNotRejected() {
        // 清洗器 take(64)：超长输入截断后仍为合法用户目标（与现有单测语义一致）。
        val long = "a".repeat(200)
        val clean = AppLinkRouter.sanitizeUsername(long)
        assertEquals("a".repeat(64), clean)
    }

    @Test
    fun notificationExtrasMapping() {
        val chat = AppLinkRouter.parseNotificationExtras(
            mapOf("open_chat_id" to "c1", "open_message_id" to "m1")
        ) as AppLinkDestination.ChatDetail
        assertEquals("c1", chat.chatId)
        assertEquals("m1", chat.messageId)

        val post = AppLinkRouter.parseNotificationExtras(mapOf("open_post_id" to "p1"))
        assertEquals("p1", (post as AppLinkDestination.PostDetail).postId)

        val user = AppLinkRouter.parseNotificationExtras(mapOf("open_username" to "alice"))
        assertEquals("alice", (user as AppLinkDestination.PublicProfile).username)

        assertNull(AppLinkRouter.parseNotificationExtras(mapOf("open_chat_id" to "   ")))
        assertNull(AppLinkRouter.parseNotificationExtras(emptyMap()))
    }

    @Test
    fun notificationExtrasBlankMessageIdTreatedAsAbsent() {
        val dest = AppLinkRouter.parseNotificationExtras(
            mapOf("open_chat_id" to "c1", "open_message_id" to "  ")
        ) as AppLinkDestination.ChatDetail
        assertEquals("c1", dest.chatId)
        assertNull(dest.messageId)
    }

    @Test
    fun notificationExtrasRejectsSlashInjection() {
        assertNull(
            AppLinkRouter.parseNotificationExtras(
                mapOf("open_chat_id" to "c1/evil", "open_message_id" to "m1")
            )
        )
        assertNull(
            AppLinkRouter.parseNotificationExtras(
                mapOf("open_chat_id" to "c1", "open_message_id" to "m1/x")
            )
        )
    }

    @Test
    fun encodeKeepsUnreserved() {
        assertEquals("abc-123_.~", AppLinkRouter.encodePathSegment("abc-123_.~"))
        assertEquals("a%2Fb", AppLinkRouter.encodePathSegment("a/b"))
    }

    // ---- 与 MainActivity 旧手写分支的行为 parity（迁移安全网） ----

    private fun publicUsernameOf(uri: String): String? {
        val res = AppLinkRouter.parseDeepLink(uri)
        return (res as? AppLinkParseResult.Accepted)
            ?.destination?.let { it as? AppLinkDestination.PublicProfile }?.username
    }

    @Test
    fun parityOldBranchAccepts() {
        // 旧分支接受的形态必须继续接受且用户名一致。
        assertEquals("alice", publicUsernameOf("maodouchat://u/alice"))
        assertEquals("bob", publicUsernameOf("https://chat.mdou.me/u/bob"))
        assertEquals("a.b-c_d", publicUsernameOf("maodouchat://u/a.b-c_d"))
    }

    @Test
    fun parityOldBranchIgnores() {
        // 旧分支忽略（不导航、继续常规流程）的形态必须继续为非 PublicProfile。
        assertNull(publicUsernameOf("maodouchat://u/"))
        assertNull(publicUsernameOf("https://chat.mdou.me/u/"))
        assertNull(publicUsernameOf("maodouchat://x/alice"))
        assertNull(publicUsernameOf("https://chat.mdou.me/x/alice"))
        assertNull(publicUsernameOf("https://chat.mdou.me"))
    }

    @Test
    fun schemeAndHostCaseInsensitive() {
        assertEquals("alice", publicUsernameOf("MAODOUCHAT://u/alice"))
        assertEquals("bob", publicUsernameOf("HTTPS://CHAT.MDOU.ME/u/bob"))
    }

    @Test
    fun strictSanitizersAcceptLegitIds() {
        // 合法形态：UUID / pairKey / 服务端 ID / 本地 m_ 前缀消息 ID。
        assertEquals(
            "550e8400-e29b-41d4-a716-446655440000",
            AppLinkRouter.sanitizeChatIdStrict("550e8400-e29b-41d4-a716-446655440000")
        )
        assertEquals("m_9f2c1a", AppLinkRouter.sanitizeMessageIdStrict("m_9f2c1a"))
        assertEquals("123456789", AppLinkRouter.sanitizePostIdStrict("123456789"))
    }

    @Test
    fun strictSanitizersRejectDelimiters() {
        assertNull(AppLinkRouter.sanitizeChatIdStrict("c1/evil"))
        assertNull(AppLinkRouter.sanitizeChatIdStrict("c1?x=1"))
        assertNull(AppLinkRouter.sanitizeMessageIdStrict("m1#frag"))
        assertNull(AppLinkRouter.sanitizePostIdStrict("p1/p2"))
        assertNull(AppLinkRouter.sanitizeChatIdStrict("   "))
    }
}
