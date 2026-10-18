package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.AiGatewayService
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.TurnCredentialService
import com.maodouchat.server.service.CallInviteRateLimiter
import com.maodouchat.server.service.BotWebhookService
import com.maodouchat.server.service.shutdown
import com.maodouchat.server.service.start
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put

// attachmentChunkPathRegex 现居 RateLimitConfig.kt（internal，同包共享）。
private val RoutingInstalledKey = AttributeKey<Unit>("MaodouchatRoutingInstalled")
private val RoutingPushServiceKey = AttributeKey<FcmPushService>("MaodouchatRoutingPushService")


fun Application.configureRouting(
    userRepo: UserRepository,
    postRepo: PostRepository,
    aiGateway: AiGateway = AiGatewayService(),
    notificationPreferenceRepo: NotificationPreferenceRepository = NotificationPreferenceRepository(),
    pushTokenRepo: PushTokenRepository = PushTokenRepository(),
    pushService: FcmPushService = FcmPushService(pushTokenRepo, notificationPreferenceRepo),
    signalingRepo: SignalingRepository = SignalingRepository(),
    callInviteRateLimiter: CallInviteRateLimiter = CallInviteRateLimiter(),
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository = com.maodouchat.server.messaging.v2.MessagingV2Repository(),
    turnCredentialService: TurnCredentialService = TurnCredentialService(
        turnUrls = ServerConfig.turnUrls,
        sharedSecret = ServerConfig.turnSharedSecret,
        ttlSeconds = ServerConfig.turnCredentialTtlSeconds,
    ),
) {
    if (attributes.contains(RoutingInstalledKey)) {
        if (attributes[RoutingPushServiceKey] !== pushService) pushService.shutdown()
        return
    }
    attributes.put(RoutingInstalledKey, Unit)
    attributes.put(RoutingPushServiceKey, pushService)
    val services = buildRoutingServiceAssembly(
        postRepo = postRepo,
        messagingV2Repository = messagingV2Repository,
        pushTokenRepo = pushTokenRepo,
    )
    val aiSummaryCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // B01：周期维护循环本体在 MaintenanceRunner，路由只做装配与启停。
    com.maodouchat.server.service.MaintenanceRunner(
        orphanGcJob = services.orphanGcJob,
        aiRepo = services.aiRepo,
        friendRepo = services.friendRepo,
        groupAuditRepo = services.groupAuditRepo,
        signalKeyRepo = services.signalKeyRepo,
        postRepo = postRepo,
        reportRepo = services.reportRepo,
        authTokenRepo = services.authTokenRepo,
    ).start(aiSummaryCleanupScope)
    val (cacheService, cacheLifecycleId) =
        com.maodouchat.server.service.CacheService.acquireLifecycle()
    val webhookLifecycleId = BotWebhookService.start()
    environment.monitor.subscribe(ApplicationStopped) {
        aiSummaryCleanupScope.cancel()
        BotWebhookService.shutdown(webhookLifecycleId)
        cacheService.shutdown(cacheLifecycleId)
        pushService.shutdown()
    }
    val limiters = buildRateLimiterAssembly()
    // 1-on-1 聊天创建锁：防止同一对用户并发创建多个聊天
    val json = Json { ignoreUnknownKeys = true }

    // 管理后台路由（/admin/*）—— 首个「明确未完成」模块落地
    configureAdminRouting(userRepo, postRepo, services.moderationRuleRepo, services.reportRepo)

    routing {
        // 全局请求体大小拦截：JSON/普通 API 限 5MB；一次性附件上传与分块上传有各自上限
        intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()
            if (path.startsWith("/api/bot/") &&
                call.request.headers["X-Bot-Token"].isNullOrBlank() &&
                call.request.headers[HttpHeaders.Authorization] != null &&
                call.request.headers[HttpHeaders.Authorization].bearerTokenOrNull() == null
            ) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid bot authorization header"))
                finish()
                return@intercept
            }
            val method = call.request.httpMethod.value
            if (method == "POST" || method == "PUT" || method == "PATCH") {
                val isAttachmentUpload = path == "/api/attachments" ||
                    path.startsWith("/api/attachment-uploads") ||
                    path.matches(attachmentChunkPathRegex)
                val isAppUpdateUpload = path == "/api/internal/app-update"
                val maxBytes = when {
                    isAttachmentUpload -> MAX_ATTACHMENT_CIPHER_BYTES
                    isAppUpdateUpload -> com.maodouchat.server.update.AppUpdatePublishPolicy.MAX_APK_BYTES
                    else -> MAX_GLOBAL_BODY_BYTES
                }
                val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
                if (contentLength != null && contentLength > maxBytes) {
                    call.respond(HttpStatusCode(413, "Request Entity Too Large"), ErrorResponse("请求体过大"))
                    finish()
                }
            }
        }
        configureConversationRoutes(
            userRepo = userRepo,
            commandService = services.conversationCommandService,
            participantRepository = services.conversationParticipantRepo,
            queryRepository = services.conversationQueryRepo,
            invitationService = services.groupInvitationService,
            pushService = pushService,
            createRateLimiter = limiters.createChatRateLimiter,
            json = json,
        )
        configureGroupInvitationRoutes(
            userRepo = userRepo,
            membershipService = services.groupMembershipService,
            invitationService = services.groupInvitationService,
            queryRepository = services.conversationQueryRepo,
            participantRepository = services.conversationParticipantRepo,
            pushService = pushService,
            json = json,
        )
        configureFriendRoutes(
            userRepository = userRepo,
            friendRepository = services.friendRepo,
            pushService = pushService,
            requestRateLimiter = limiters.friendRequestRateLimiter,
            json = json,
        )
        configureClientSyncRoutes(
            chatFolderRepository = services.chatFolderRepo,
            clientPrefsRepository = services.clientPrefsRepo,
        )
        configureGroupAdminRoutes(
            userRepo = userRepo,
            groupMembershipService = services.groupMembershipService,
            groupProfileRepo = services.groupProfileRepo,
            groupModerationRepo = services.groupModerationRepo,
            groupInvitationService = services.groupInvitationService,
            conversationQueryRepo = services.conversationQueryRepo,
            conversationParticipantRepo = services.conversationParticipantRepo,
            avatarRateLimiter = limiters.avatarRateLimiter,
            groupAuditRepo = services.groupAuditRepo,
            signalKeyRepo = services.signalKeyRepo,
            senderKeyDistributionRepo = services.senderKeyDistributionRepo,
            json = json,
        )
        configureSignalCallRoutes(
            userRepo = userRepo,
            signalKeyRepository = services.signalKeyRepo,
            conversationQueryRepository = services.conversationQueryRepo,
            preKeyFetchLimiter = limiters.preKeyFetchTracker,
            signalingRepo = signalingRepo,
            callInviteRateLimiter = callInviteRateLimiter,
            turnCredentialService = turnCredentialService,
            pushService = pushService,
            json = json,
        )
        configureBotRoutes(
            userRepo = userRepo,
            starMessageRepo = services.starMessageRepo,
            pinnedMessageRepo = services.pinnedMessageRepo,
            serviceMessageRepo = services.serviceMessageRepo,
            groupMembershipService = services.groupMembershipService,
            groupProfileRepo = services.groupProfileRepo,
            groupModerationRepo = services.groupModerationRepo,
            groupInvitationService = services.groupInvitationService,
            conversationCommandService = services.conversationCommandService,
            conversationParticipantRepo = services.conversationParticipantRepo,
            conversationQueryRepo = services.conversationQueryRepo,
            botSendRateLimiter = limiters.botSendRateLimiter,
            json = json,
            messagingV2Repository = messagingV2Repository,
        )

        // Authentication and public identity routes.
        configureAuthRoutes(
            cacheService = cacheService,
            userRepo = userRepo,
            authTokenRepo = services.authTokenRepo,
            pushTokenRepo = pushTokenRepo,
            loginIpRateLimiter = limiters.loginIpRateLimiter,
            loginEmailRateLimiter = limiters.loginEmailRateLimiter,
            sendCodeRateLimiter = limiters.sendCodeRateLimiter,
            sendCodeIpRateLimiter = limiters.sendCodeIpRateLimiter,
            publicProfileRateLimiter = limiters.publicProfileRateLimiter,
            loginGate = limiters.loginGate,
            sessionService = services.sessionService,
        )

        // ─── 官网静态页面（无需认证） ─────────────

        configurePublicSiteRoutes()
        configureAttachmentRoutes(
            userRepo = userRepo,
            encryptedAttachmentRepo = services.encryptedAttachmentRepo,
            conversationParticipantRepo = services.conversationParticipantRepo,
            conversationQueryRepo = services.conversationQueryRepo,
            rateLimiter = limiters.aiRateLimiter,
            messagingV2Repository = messagingV2Repository,
        )
        configureAuthSessionRoutes(
            mfaService = com.maodouchat.server.service.MfaService(),
            sessionService = services.sessionService,
            totpManageRateLimiter = limiters.totpManageRateLimiter,
        )
        configureAccountRoutes(
            userRepo = userRepo,
            postRepo = postRepo,
            authTokenRepo = services.authTokenRepo,
            pushTokenRepo = pushTokenRepo,
            sessionService = services.sessionService,
            notificationPreferenceRepo = notificationPreferenceRepo,
            nearbyRepo = services.nearbyRepo,
            cacheService = cacheService,
            groupMediaReferenceRepo = services.groupMediaReferenceRepo,
            encryptedAttachmentRepo = services.encryptedAttachmentRepo,
            conversationParticipantRepo = services.conversationParticipantRepo,
            conversationQueryRepo = services.conversationQueryRepo,
            userSearchRateLimiter = limiters.userSearchRateLimiter,
            nearbyUpdateRateLimiter = limiters.nearbyUpdateRateLimiter,
            nearbyQueryRateLimiter = limiters.nearbyQueryRateLimiter,
            avatarRateLimiter = limiters.avatarRateLimiter,
            json = json,
        )
        configurePollLegacyRoutes(
            userRepo = userRepo,
            conversationParticipantRepo = services.conversationParticipantRepo,
            conversationQueryRepo = services.conversationQueryRepo,
            json = json,
        )
        configureBotUserRoutes(
            userRepo = userRepo,
            conversationParticipantRepo = services.conversationParticipantRepo,
            commandService = services.conversationCommandService,
            conversationQueryRepo = services.conversationQueryRepo,
            groupMembershipService = services.groupMembershipService,
            botCreateRateLimiter = limiters.botCreateRateLimiter,
            botTokenRateLimiter = limiters.botTokenRateLimiter,
            createChatRateLimiter = limiters.createChatRateLimiter,
            json = json,
        )
        configureReportModerationRoutes(
            userRepo = userRepo,
            postRepo = postRepo,
            reportRepo = services.reportRepo,
            moderationRuleRepo = services.moderationRuleRepo,
            authTokenRepo = services.authTokenRepo,
            pushTokenRepo = pushTokenRepo,
            sessionService = services.sessionService,
            conversationParticipantRepo = services.conversationParticipantRepo,
            reportRateLimiter = limiters.reportRateLimiter,
            json = json,
            messagingV2Repository = messagingV2Repository,
        )
        configureSocialPostRoutes(
            userRepo = userRepo,
            postRepo = postRepo,
            moderationRuleRepo = services.moderationRuleRepo,
            aiGateway = aiGateway,
            pushService = pushService,
            conversationParticipantRepo = services.conversationParticipantRepo,
            conversationQueryRepo = services.conversationQueryRepo,
            starMessageRepo = services.starMessageRepo,
            pinnedMessageRepo = services.pinnedMessageRepo,
            postRateLimiter = limiters.postRateLimiter,
            postImageRateLimiter = limiters.postImageRateLimiter,
            commentRateLimiter = limiters.commentRateLimiter,
            postLikeRateLimiter = limiters.postLikeRateLimiter,
            commentLikeRateLimiter = limiters.commentLikeRateLimiter,
            json = json,
        )
    }
}
