package com.maodouchat.server.plugins

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteRegistrySplitTest {
    private fun source(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/maodouchat/server/plugins/$name"),
            File("server/src/main/kotlin/com/maodouchat/server/plugins/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("cannot locate $name from ${File(".").absolutePath}")
    }

    @Test
    fun `Bot API registry delegates without owning endpoints`() {
        val registry = source("BotApiRouting.kt")
        val core = source("BotCoreRouting.kt")

        assertTrue(registry.lineSequence().count() <= 50)
        assertTrue("configureBotCoreRoutes(" in registry)
        assertTrue("configureBotPresentationRoutes(" in core)
        assertFalse(ENDPOINT_DECLARATION.containsMatchIn(registry))
        // Bot API 端点已按子域拆分为多个实现模块，计数必须覆盖全部模块，防止任一模块端点被意外删减。
        // 961 → 940：21 处响应体 put("…", …) 收敛为 putJsonElement("…", …)，行首不再命中 put(" 正则；端点本身无增减。
        val botRouteModules = BOT_ROUTE_MODULES.joinToString("\n") { source(it) }
        assertEquals(940, ENDPOINT_DECLARATION.findAll(botRouteModules).count())
    }

    @Test
    fun `admin registry delegates and observability remains registered`() {
        val registry = source("AdminRouting.kt")
        val management = source("AdminManagementRouting.kt")
        // Observability 已按域拆为 3 个实现簇（health / analytics / audit），路径断言覆盖全部簇文件。
        val observability = listOf(
            "AdminObservabilityRouting.kt",
            "AdminObservabilityHealthRoutes.kt",
            "AdminObservabilityAnalyticsRoutes.kt",
            "AdminObservabilityAuditRoutes.kt",
        ).joinToString("\n") { source(it) }

        assertTrue(registry.lineSequence().count() <= 30)
        assertTrue("configureAdminManagementRouting(" in registry)
        assertTrue("configureAdminObservabilityRoutes(ServerConfig)" in management)
        assertFalse(ENDPOINT_DECLARATION.containsMatchIn(registry))
        // 管理后台路由已按子域拆分为多个实现模块（Management / Observability / Moderation / …），
        // 端点计数必须覆盖全部模块，防止任一模块的端点被意外删减。
        val adminRouteModules = ADMIN_ROUTE_MODULES.joinToString("\n") { source(it) }
        // 381 → 326：55 处响应体 put("…", …) 收敛为 putJsonElement("…", …)，行首不再命中 put(" 正则；端点本身无增减。
        assertEquals(326, ENDPOINT_DECLARATION.findAll(adminRouteModules).count())
        listOf(
            "channel-health",
            "dashboard",
            "system-stats",
            "trends",
            "online",
            "ranking",
            "storage",
            "rich-trends",
            "audit-logs",
        ).forEach { path -> assertTrue("get(\"/$path" in observability, path) }
    }

    @Test
    fun `primary Bot sender uses atomic service publishing`() {
        val core = source("BotCoreSendRoutes.kt")
        val sender = core.substringAfter("post(\"/api/bot/sendMessage\")")
            .substringBefore("post(\"/api/bot/editMessage\")")

        assertTrue("publishBotServiceMessage(" in sender)
        assertFalse("serviceMessageRepo.insert(" in sender)
        assertFalse("fanoutBotMessage(" in sender)
    }

    @Test
    fun `primary registry delegates public update and profile routes`() {
        val registry = source("Routing.kt")
        val update = listOf(
            "PublicUpdateRouting.kt",
            "PublicAppUpdateRoutes.kt",
            "InternalAppUpdateRoutes.kt",
            "ServerStatusRoutes.kt",
            "WebRtcBinaryRoutes.kt",
        ).joinToString("\n") { source(it) }
        val profile = source("PublicProfileRouting.kt")

        assertTrue("configurePublicUpdateRoutes(cacheService)" in registry)
        assertTrue("configurePublicProfileRoutes(" in registry)
        listOf(
            "/api/public/updates",
            "/api/public/app-update/latest.apk",
            "/api/internal/app-update",
            "/api/public/status",
            "/api/webrtc/lib/{abi}",
            "/api/public/profile/{username}",
            "/u/{username}",
        ).forEach { path ->
            val declaration = Regex("(?m)^\\s*(get|post|put|delete|patch)\\(\\\"${Regex.escape(path)}\\\"\\)")
            assertFalse(declaration.containsMatchIn(registry), path)
            assertTrue(declaration.containsMatchIn(update) || declaration.containsMatchIn(profile), path)
        }
    }

    @Test
    fun `priority route families are delegated with endpoint parity`() {
        val registry = source("Routing.kt")
        // 附件/动态路由经两层委托：Routing.kt 只调装配入口，内层各族调用收在装配文件里。
        val attachmentAssembly = source("AttachmentRoutesAssembly.kt")
        val socialPostAssembly = source("SocialPostRoutesAssembly.kt")
        // Bot/群管理路由经两层委托：Routing.kt 只调装配入口，内层各族调用收在装配文件里。
        val botAssembly = source("BotRoutesAssembly.kt")
        val groupAdminAssembly = source("GroupAdminRoutesAssembly.kt")
        val modules = listOf(
            source("AuthAccountRoutes.kt"),
            source("AuthLoginRoutes.kt"),
            source("AuthLoginLoginRoutes.kt"),
            source("AuthLoginRefreshRoutes.kt"),
            source("AuthLoginLogoutRoutes.kt"),
            source("AuthSessionRoutes.kt"),
            source("AuthSessionLogoutRoutes.kt"),
            source("AuthSessionTotpRoutes.kt"),
            source("AccountProfileReadRoutes.kt"),
            source("AccountProfileWriteRoutes.kt"),
            source("AccountAvatarRoutes.kt"),
            source("AccountProfileUpdateRoutes.kt"),
            source("AccountUsernameRoutes.kt"),
            source("AccountSecurityRoutes.kt"),
            source("AccountSecurityPasswordRoutes.kt"),
            source("AccountSecurityDeleteRoutes.kt"),
            source("AccountPushRoutes.kt"),
            source("AccountPushKeyRoutes.kt"),
            source("AccountPushTokenRoutes.kt"),
            source("AccountDiscoveryRoutes.kt"),
            source("AccountUserSearchRoutes.kt"),
            source("AccountNearbyRoutes.kt"),
            source("AccountPrivacyRoutes.kt"),
            source("AccountPrivacyPolicyRoutes.kt"),
            source("AccountNotificationSettingsRoutes.kt"),
            source("AttachmentUploadSessionRoutes.kt"),
            source("AttachmentUploadSessionCreateRoutes.kt"),
            source("AttachmentUploadSessionQueryRoutes.kt"),
            source("AttachmentUploadSessionChunkRoutes.kt"),
            source("AttachmentDownloadRoutes.kt"),
            source("AttachmentDirectUploadRoutes.kt"),
            source("AttachmentDirectDownloadRoutes.kt"),
            source("AttachmentDirectDeleteRoutes.kt"),
            source("ReportUserBlockRoutes.kt"),
            source("ReportUserReportRoutes.kt"),
            source("ModeratorReviewRoutes.kt"),
            source("ModeratorReviewQueryRoutes.kt"),
            source("ModeratorReviewManageRoutes.kt"),
            source("ModeratorReviewStatusRoutes.kt"),
            source("ModeratorReviewActionRoutes.kt"),
            source("AdminModerationRoutes.kt"),
            source("SocialPostStarPinRoutes.kt"),
            source("SocialPostStarRoutes.kt"),
            source("SocialPostPinRoutes.kt"),
            source("SocialPostReadRoutes.kt"),
            source("SocialPostPublishRoutes.kt"),
            source("SocialPostManageRoutes.kt"),
            source("SocialPostLikeRoutes.kt"),
            source("SocialPostCommentRoutes.kt"),
            source("SocialPostCommentReadRoutes.kt"),
            source("SocialPostCommentWriteRoutes.kt"),
            source("SocialPostCommentContentRoutes.kt"),
            source("SocialPostCommentLikeRoutes.kt"),
            source("SocialPostFileRoutes.kt"),
            source("PublicSiteRouting.kt"),
            source("PollLegacyRouting.kt"),
            source("PollLegacyQueryRoutes.kt"),
            source("PollLegacyManageRoutes.kt"),
        )

        listOf(
            "configureAuthAccountRoutes(",
            "configureAuthLoginRoutes(",
            "configureAuthSessionRoutes(",
            "configureAccountRoutes(",
            "configureAttachmentRoutes(",
            "configureReportModerationRoutes(",
            "configureSocialPostRoutes(",
            "configureBotRoutes(",
            "configureGroupAdminRoutes(",
            "configurePublicSiteRoutes()",
            "configurePollLegacyRoutes(",
        ).forEach { registration -> assertTrue(registration in registry, registration) }
        // 装配层必须继续把内层各族接上，防止端点在搬运中被静默丢掉。
        listOf(
            "configureEncryptedAttachmentUploadSessionRoutes(",
            "configureEncryptedAttachmentDownloadRoutes(",
        ).forEach { registration -> assertTrue(registration in attachmentAssembly, registration) }
        listOf(
            "configureSocialPostStarPinRoutes(",
            "configureSocialPostCoreRoutes(",
            "configureSocialPostLikeRoutes(",
            "configureSocialPostCommentRoutes(",
            "configureSocialPostFileRoutes(",
        ).forEach { registration -> assertTrue(registration in socialPostAssembly, registration) }
        // 装配层必须继续把内层各族接上，防止端点在搬运中被静默丢掉。
        listOf(
            "configureBotProbeRoutes(",
            "configureBotRuntimeFlagRoutes(",
            "configureBotHintRoutes(",
            "configureBotApiRoutes(",
        ).forEach { registration -> assertTrue(registration in botAssembly, registration) }
        listOf(
            "configureGroupAdminMemberRoutes(",
            "configureGroupAdminProfileRoutes(",
            "configureGroupAdminAuditRoutes(",
        ).forEach { registration -> assertTrue(registration in groupAdminAssembly, registration) }

        listOf(
            "/api/auth/login",
            "/api/auth/logout-all",
            "/api/users/me",
            "/api/attachment-uploads",
            "/api/attachments/{id}",
            "/api/reports",
            "/api/moderator/reports/{reportId}/action",
            "/api/posts",
            "/api/chats/{chatId}/polls",
            "/api/files/post-image/{filename}",
        ).forEach { path ->
            val declaration = Regex("(?m)^\\s*(get|post|put|delete|patch)\\(\\\"${Regex.escape(path)}\\\"\\)")
            assertFalse(declaration.containsMatchIn(registry), path)
            assertTrue(modules.any { declaration.containsMatchIn(it) }, path)
        }
    }

    private companion object {
        val ENDPOINT_DECLARATION = Regex("(?m)^\\s*(get|post|put|delete|patch)\\(\\\"")
        val ADMIN_ROUTE_MODULES = listOf(
            "AdminManagementRouting.kt",
            "AdminSessionRoutes.kt",
            "AdminUserSessionRoutes.kt",
            "AdminUserSessionQueryRoutes.kt",
            "AdminUserSessionRevokeRoutes.kt",
            "AdminUserSessionTotpRoutes.kt",
            "AdminOpsRoutes.kt",
            "AdminMessageSearchRoutes.kt",
            "AdminBroadcastRoutes.kt",
            "AdminModeratorSwitchRoutes.kt",
            "AdminBotCommandLogRoutes.kt",
            "AdminConsoleRoutes.kt",
            "AdminObservabilityRouting.kt",
            "AdminObservabilityHealthRoutes.kt",
            "AdminObservabilityAnalyticsRoutes.kt",
            "AdminObservabilityAuditRoutes.kt",
            "AdminModerationRouting.kt",
            "AdminReportRoutes.kt",
            "AdminReportQueryRoutes.kt",
            "AdminReportWriteRoutes.kt",
            "AdminReportStatusRoutes.kt",
            "AdminReportActionRoutes.kt",
            "AdminModerationRuleRoutes.kt",
            "AdminRiskEventRoutes.kt",
            "AdminContentRouting.kt",
            "AdminContentQueryRoutes.kt",
            "AdminContentWriteRoutes.kt",
            "AdminChatsRouting.kt",
            "AdminDiagnosticsRouting.kt",
            "AdminDiagnosticsQueryRoutes.kt",
            "AdminDiagnosticsWriteRoutes.kt",
            "AdminUsersRouting.kt",
            "AdminUserQueryRoutes.kt",
            "AdminUserRestrictionRoutes.kt",
            "AdminUserDeleteRoutes.kt",
            "AdminSystemRouting.kt",
            "AdminSystemQueryRoutes.kt",
            "AdminSystemWriteRoutes.kt",
            "AdminExportsContentRoutes.kt",
            "AdminExportsChatRoutes.kt",
            "AdminExportsRiskRoutes.kt",
            "AdminExportsSettingRoutes.kt",
            "AdminExportsOpsRoutes.kt",
            "AdminExportsDataRoutes.kt",
            "AdminExportsWatermarkRoutes.kt",
            "AdminExportsUserRoutes.kt",
            "AdminExportsIdentityRoutes.kt",
            "AdminExportsSocialRoutes.kt",
            "AdminExportsPresenceRoutes.kt",
            "AdminBulkChatRoutes.kt",
            "AdminBulkUserRoutes.kt",
            "AdminBulkSessionRoutes.kt",
            "AdminBulkDispositionRoutes.kt",
            "AdminBulkBanRoutes.kt",
            "AdminBulkSuspendRoutes.kt",
            "AdminBulkMessageRestrictRoutes.kt",
            "AdminBulkMessageRestrictToggleRoutes.kt",
            "AdminBulkMessageRestrictTimedRoutes.kt",
            "AdminBulkPostRestrictRoutes.kt",
            "AdminBulkClearRestrictionsRoutes.kt",
            "AdminBulkProfileRoutes.kt",
            "AdminBulkProfileSearchableRoutes.kt",
            "AdminBulkProfilePresenceRoutes.kt",
        )
        val BOT_ROUTE_MODULES = listOf(
            "BotCoreRouting.kt",
            "BotCoreSendRoutes.kt",
            "BotCoreEditRoutes.kt",
            "BotCoreChatRoutes.kt",
            "BotPresentationRouting.kt",
            "BotInfoRouting.kt",
            "BotInfoWebhookRoutes.kt",
            "BotInfoProfileRoutes.kt",
            "BotInfoChatsRoutes.kt",
            "BotCommandRouting.kt",
            "BotCommandQueryRoutes.kt",
            "BotCommandWriteRoutes.kt",
            "BotChatActionRouting.kt",
            "BotChatActionWriteRoutes.kt",
            "BotChatActionSendRoutes.kt",
            "BotChatActionPinRoutes.kt",
            "BotChatActionQueryRoutes.kt",
            "BotWebhookRouting.kt",
            "BotMemberRouting.kt",
            "BotMemberQueryRoutes.kt",
            "BotMemberRestrictRoutes.kt",
            "BotMemberRestrictMuteRoutes.kt",
            "BotMemberRestrictBanRoutes.kt",
            "BotMemberPromoteRoutes.kt",
            "BotChatAdminRouting.kt",
            "BotChatAdminWriteRoutes.kt",
            "BotChatAdminQueryRoutes.kt",
            "BotCallbackRouting.kt",
            "BotCallbackWriteRoutes.kt",
            "BotCallbackQueryRoutes.kt",
            "BotPollRouting.kt",
            "BotPollSendRoutes.kt",
            "BotPollVoteRoutes.kt",
            "BotChatModerationRouting.kt",
            "BotChatLeaveRoutes.kt",
            "BotChatDeleteMessageRoutes.kt",
            "BotMessageForwardingRouting.kt",
            "BotMessageForwardRoutes.kt",
            "BotForwardMessageRoutes.kt",
            "BotCopyMessageRoutes.kt",
            "BotMemberPromotionRouting.kt",
            "BotProfileRouting.kt",
            "BotPresentationWidgetsRouting.kt",
            "BotPresentationWidgetsProbeRoutes.kt",
            "BotPresentationWidgetsStatusRoutes.kt",
            "BotPresentationWidgetsRichRoutes.kt",
            "BotPresentationStatusRouting.kt",
            "BotPresentationStatusQueryRoutes.kt",
            "BotPresentationStatusQueryStatusRoutes.kt",
            "BotPresentationStatusQueryMetaRoutes.kt",
            "BotPresentationStatusQueryFeaturesRoutes.kt",
            "BotPresentationStatusPushRoutes.kt",
            "BotPresentationStatusPushProgressRoutes.kt",
            "BotPresentationStatusPushNotifyRoutes.kt",
            "BotPresentationStatusPushDividerRoutes.kt",
            "BotPresentationCardsRouting.kt",
            "BotPresentationCardsSimpleRoutes.kt",
            "BotPresentationEchoRoutes.kt",
            "BotPresentationSimpleSendRoutes.kt",
            "BotPresentationSimpleSendToastRoutes.kt",
            "BotPresentationSimpleSendKeyValueRoutes.kt",
            "BotPresentationSimpleSendNoticeRoutes.kt",
            "BotPresentationCardsRichRoutes.kt",
            "BotPresentationCardsQuoteRoutes.kt",
            "BotPresentationCardsDataRoutes.kt",
            "BotPresentationCardsJsonCardRoutes.kt",
            "BotPresentationCardsTimelineRoutes.kt",
            "BotPresentationCardsMetricRoutes.kt",
            "BotPresentationCardsFlowRoutes.kt",
            "BotPresentationHintCardsRoutes.kt",
            "BotPresentationHintCardsMentionRoutes.kt",
            "BotPresentationHintCardsContactRoutes.kt",
            "BotPresentationHintSafetyRoutes.kt",
            "BotPresentationHintSafetyInviteRoutes.kt",
            "BotPresentationHintSafetyContentRoutes.kt",
            "BotPresentationHintSafetySecretRoutes.kt",
            "BotPresentationHintMediaRoutes.kt",
            "BotPresentationHintInfoRoutes.kt",
            "BotPresentationHintInfoQrRoutes.kt",
            "BotPresentationHintInfoFileRoutes.kt",
            "BotPresentationHintGeoRoutes.kt",
            "BotPresentationHintVisualRoutes.kt",
            "BotPresentationHintVisualMediaRoutes.kt",
            "BotPresentationHintVisualPhotoRoutes.kt",
            "BotPresentationHintVisualVideoRoutes.kt",
            "BotPresentationHintVisualGifRoutes.kt",
            "BotPresentationHintVisualWatermarkRoutes.kt",
            "BotPresentationProbesRouting.kt",
            "BotPresentationProbesHealthRoutes.kt",
            "BotPresentationProbesFlagsRoutes.kt",
            "BotChatInviteRouting.kt",
            "BotChatInviteLinkRoutes.kt",
            "BotChatInviteLinkExportRoutes.kt",
            "BotChatInviteLinkRevokeRoutes.kt",
            "BotChatAvatarRoutes.kt",
            "BotChatPinRoutes.kt",
            "BotMediaSendRoutes.kt",
            "BotMediaImageRoutes.kt",
            "BotMediaStickerPhotoRoutes.kt",
            "BotMediaVideoAnimationRoutes.kt",
            "BotMediaAudioDocRoutes.kt",
            "BotMediaVoiceAudioRoutes.kt",
            "BotMediaDocumentReadRoutes.kt",
            "BotMediaGroupRoutes.kt",
            "BotMediaGroupInviteRoutes.kt",
            "BotMediaGroupMemberRoleRoutes.kt",
            "BotMediaProfileRoutes.kt",
            "BotMediaProfileReadRoutes.kt",
            "BotMediaBehaviorRoutes.kt",
            "BotGeoRouting.kt",
            "BotGeoLocationRoutes.kt",
            "BotGeoQueryRoutes.kt",
            "BotGeoMiscRoutes.kt",
            "BotGeoMiscProfileRoutes.kt",
            "BotGeoMiscContactRoutes.kt",
            "BotGeoMiscPinRoutes.kt",
            "BotMessagingVariantsRouting.kt",
            "BotMessagingSendVariantsRoutes.kt",
            "BotMessagingSendSilentRoutes.kt",
            "BotMessagingSendMarkdownRoutes.kt",
            "BotMessagingSendNudgeRoutes.kt",
            "BotMessagingMemberRoutes.kt",
            "BotMessagingCommandRoutes.kt",
            "BotPollEditRouting.kt",
            "BotPollEditInfoRoutes.kt",
            "BotPollEditOpsRoutes.kt",
            "BotReactionRouting.kt",
            "BotCommandStatsRoutes.kt",
            "BotMessageFormatRoutes.kt",
            "BotReactionCoreRoutes.kt",
            "BotChatMiscRouting.kt",
            "BotChatMiscWriteRoutes.kt",
            "BotChatMiscQueryRoutes.kt",
            "BotPollQuizRouting.kt",
            "BotPollQuizInfoRoutes.kt",
            "BotPollQuizSendRoutes.kt",
        )
    }
}
