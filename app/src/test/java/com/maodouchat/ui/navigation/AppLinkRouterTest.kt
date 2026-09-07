package com.maodouchat.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// NotificationTarget lives in the same package (NavTargets.kt).

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
        // 生产侧分享/扫码载荷：冒号协议（无 ://）必须与 QR 解析收敛到同一 GroupInvite。
        val token = "A".repeat(43)
        val c = AppLinkRouter.parseDeepLink("maodouchat:chat-invite:v1:$token")
        assertIs<AppLinkParseResult.Accepted>(c)
        assertEquals(token, (c.destination as AppLinkDestination.GroupInvite).code)
        assertEquals("join_group_invite/$token", c.destination.toRoute())
    }

    @Test
    fun chatInviteColonFormRejectsShortOrInjectedToken() {
        assertIs<AppLinkParseResult.Rejected>(
            AppLinkRouter.parseDeepLink("maodouchat:chat-invite:v1:short")
        )
        assertIs<AppLinkParseResult.Rejected>(
            AppLinkRouter.parseDeepLink("maodouchat:chat-invite:v1:${"a".repeat(40)}/evil")
        )
    }

    @Test
    fun groupInviteTargetMapsToDestination() {
        val target = NotificationTarget.GroupInvite(
            code = "B".repeat(40),
            sessionGeneration = 1L,
            ownerUserId = "u1",
        )
        val dest = target.toDestination() as AppLinkDestination.GroupInvite
        assertEquals("B".repeat(40), dest.code)
        assertEquals("join_group_invite/${"B".repeat(40)}", dest.toRoute())
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
    fun aiTasksDestinationRoute() {
        val dest = AppLinkDestination.AiTasksChat("c1")
        assertTrue(dest.requiresAuth)
        // 与 Routes.aiTasks 同构。
        assertEquals("ai_tasks/c1", dest.toRoute())
    }

    @Test
    fun notificationExtrasAiTasks() {
        val dest = AppLinkRouter.parseNotificationExtras(
            mapOf("open_ai_tasks_chat_id" to "c9")
        )
        assertEquals(AppLinkDestination.AiTasksChat("c9"), dest)
    }

    @Test
    fun dispatchParityWithRoutesBuilders() {
        // MainActivity 派发改走 toRoute()：锁定与旧 Routes.* builder 逐字一致。
        assertEquals(
            "chat_detail/c1",
            (AppLinkDestination.ChatDetail("c1") as AppLinkDestination).toRoute()
        )
        assertEquals(
            "chat_detail/c1?messageId=m1",
            AppLinkDestination.ChatDetail("c1", "m1").toRoute()
        )
        assertEquals("ai_tasks/c1", AppLinkDestination.AiTasksChat("c1").toRoute())
        assertEquals(
            "post/p1?comment=",
            AppLinkDestination.PostDetail("p1").toRoute()
        )
        assertEquals(
            "post/p1?comment=c2",
            AppLinkDestination.PostDetail("p1", "c2").toRoute()
        )
        assertEquals("public_profile/alice", AppLinkDestination.PublicProfile("alice").toRoute())
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
    fun callAndUserIdsAreStrictSanitized() {
        val uuid = "123e4567-e89b-42d3-a456-426614174000"
        assertEquals(uuid, AppLinkRouter.sanitizeCallIdStrict(uuid))
        assertEquals("u_abc-123", AppLinkRouter.sanitizeUserIdStrict("u_abc-123"))
        // /?# 直接拒绝（而非截断），避免误定向响铃。
        assertNull(AppLinkRouter.sanitizeCallIdStrict("c1/evil"))
        assertNull(AppLinkRouter.sanitizeCallIdStrict("c1?x=1"))
        assertNull(AppLinkRouter.sanitizeCallIdStrict("c1#frag"))
        assertNull(AppLinkRouter.sanitizeUserIdStrict("u1/evil"))
        assertNull(AppLinkRouter.sanitizeCallIdStrict("  "))
        assertNull(AppLinkRouter.sanitizeCallIdStrict(""))
    }

    @Test
    fun deepLinkPatternsAreSingleSourced() {
        // NavGraph/Manifest 与此逐项对应；增删模式必须同步三处。
        assertEquals(
            listOf(
                "https://chat.mdou.me/u/{username}",
                "https://chat.mdou.me/u/{username}?embed={embed}",
                "maodouchat://u/{username}",
            ),
            AppLinkRouter.publicProfileDeepLinkPatterns,
        )
        assertEquals(
            listOf(
                "https://chat.mdou.me/join/{code}",
                "maodouchat://invite/{code}",
            ),
            AppLinkRouter.groupInviteDeepLinkPatterns,
        )
        // 每个模式都有对应的解析覆盖。
        assertTrue(
            AppLinkRouter.parseDeepLink("https://chat.mdou.me/u/alice") is AppLinkParseResult.Accepted
        )
        assertTrue(
            AppLinkRouter.parseDeepLink("https://chat.mdou.me/u/alice?embed=1") is AppLinkParseResult.Accepted
        )
        assertTrue(
            AppLinkRouter.parseDeepLink("maodouchat://u/alice") is AppLinkParseResult.Accepted
        )
        assertTrue(
            AppLinkRouter.parseDeepLink("https://chat.mdou.me/join/XYZ-9") is AppLinkParseResult.Accepted
        )
        assertTrue(
            AppLinkRouter.parseDeepLink("maodouchat://invite/ABCdef-12_") is AppLinkParseResult.Accepted
        )
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
    fun legacyCenterDeeplinkChatAndAiTasks() {
        val chat = AppLinkRouter.parseLegacyCenterDeeplink("maodouchat:chat:c1")
            as AppLinkDestination.ChatDetail
        assertEquals("c1", chat.chatId)
        assertEquals("chat_detail/c1", chat.toRoute())

        val ai = AppLinkRouter.parseLegacyCenterDeeplink("maodouchat:ai_tasks:c9")
            as AppLinkDestination.AiTasksChat
        assertEquals("c9", ai.chatId)
        assertEquals("ai_tasks/c9", ai.toRoute())
    }

    @Test
    fun legacyCenterDeeplinkPostWithComment() {
        val post = AppLinkRouter.parseLegacyCenterDeeplink("maodouchat:post:p1?comment=cm2")
            as AppLinkDestination.PostDetail
        assertEquals("p1", post.postId)
        assertEquals("cm2", post.commentId)
        assertEquals("post/p1?comment=cm2", post.toRoute())
    }

    @Test
    fun legacyCenterDeeplinkTabTargets() {
        assertEquals(
            AppLinkDestination.MissedCallsTab,
            AppLinkRouter.parseLegacyCenterDeeplink("maodouchat:missed_calls"),
        )
        assertEquals(
            AppLinkDestination.ContactsTab,
            AppLinkRouter.parseLegacyCenterDeeplink("maodouchat:contacts"),
        )
        assertEquals(
            AppLinkDestination.GroupInvitesTab,
            AppLinkRouter.parseLegacyCenterDeeplink("maodouchat:group_invites"),
        )
    }

    @Test
    fun legacyCenterDeeplinkRejectsInjection() {
        assertNull(AppLinkRouter.parseLegacyCenterDeeplink("maodouchat:chat:c1/evil"))
        assertNull(AppLinkRouter.parseLegacyCenterDeeplink("maodouchat:chat:c1?x=1"))
        assertNull(AppLinkRouter.parseLegacyCenterDeeplink("maodouchat:post:p1/p2"))
        assertNull(AppLinkRouter.parseLegacyCenterDeeplink("maodouchat:post:p1?comment=c/x"))
        assertNull(AppLinkRouter.parseLegacyCenterDeeplink("maodouchat:unknown:x"))
        assertNull(AppLinkRouter.parseLegacyCenterDeeplink(""))
        assertNull(AppLinkRouter.parseLegacyCenterDeeplink("   "))
    }

    @Test
    fun resolveUserFacingUrlPrefersInAppDeepLinks() {
        val profile = AppLinkRouter.resolveUserFacingUrl("https://chat.mdou.me/u/alice")
        assertIs<AppLinkParseResult.Accepted>(profile)
        assertEquals(AppLinkDestination.PublicProfile("alice"), profile.destination)

        val invite = AppLinkRouter.resolveUserFacingUrl("https://chat.mdou.me/join/" + "A".repeat(40))
        assertIs<AppLinkParseResult.Accepted>(invite)
        assertIs<AppLinkDestination.GroupInvite>(invite.destination)
    }

    @Test
    fun resolveUserFacingUrlMapsGenericHttpToExternal() {
        val result = AppLinkRouter.resolveUserFacingUrl("https://example.com/path?q=1")
        assertIs<AppLinkParseResult.Accepted>(result)
        assertEquals(
            AppLinkDestination.ExternalUrl("https://example.com/path?q=1"),
            result.destination,
        )
        // Untrusted host must not become PublicProfile even if path looks like /u/.
        val evil = AppLinkRouter.resolveUserFacingUrl("https://evil.com/u/alice")
        assertIs<AppLinkParseResult.Accepted>(evil)
        assertEquals(AppLinkDestination.ExternalUrl("https://evil.com/u/alice"), evil.destination)
    }

    @Test
    fun resolveUserFacingUrlRejectsDangerousSchemes() {
        assertIs<AppLinkParseResult.Rejected>(
            AppLinkRouter.resolveUserFacingUrl("javascript:alert(1)")
        )
        assertIs<AppLinkParseResult.Rejected>(
            AppLinkRouter.resolveUserFacingUrl("file:///etc/passwd")
        )
        assertIs<AppLinkParseResult.Rejected>(
            AppLinkRouter.resolveUserFacingUrl("intent://evil#Intent;end")
        )
        assertNull(AppLinkRouter.sanitizeHttpUrl("http://exa mple.com"))
        assertNull(AppLinkRouter.sanitizeHttpUrl("https:///no-host"))
    }
}
