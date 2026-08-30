package com.maodouchat.server.plugins

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.SealedSenderCertificateService
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.AiGateway
import com.maodouchat.server.service.AiGatewayService
import com.maodouchat.server.service.ContentModerationService
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.EncryptedAttachmentStorage
import com.maodouchat.server.service.TurnCredentialService
import com.maodouchat.server.service.CallInviteRateLimiter
import com.maodouchat.server.service.WebRtcBinaryService
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import java.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val MEDIA_ORPHAN_GRACE_MS = 7L * 24L * 60L * 60L * 1_000L
private val RoutingInstalledKey = AttributeKey<Unit>("MaodouchatRoutingInstalled")
private val RoutingPushServiceKey = AttributeKey<FcmPushService>("MaodouchatRoutingPushService")

private fun loadPublicHtml(page: String): String? =
    Thread.currentThread().contextClassLoader?.getResource("public/$page.html")?.readText()
        ?: object {}.javaClass.classLoader.getResource("public/$page.html")?.readText()

private suspend fun ApplicationCall.respondPublicHtml(page: String, fallback: String = "<h1>毛豆聊天</h1>") {
    response.header(HttpHeaders.CacheControl, "no-cache, must-revalidate")
    respondText(loadPublicHtml(page) ?: fallback, ContentType.Text.Html)
}

/** MASTER_ADMINS inherit the limited content-review permissions even when isModerator is false. */


internal suspend fun notifyGroupRevisionChanged(
    queryRepository: ConversationQueryRepository,
    participantRepository: ConversationParticipantRepository,
    json: Json,
    chatId: String,
    reason: String,
    actorId: String,
    targetUserId: String? = null,
    recipientIds: List<String>? = null
) {
    val chat = queryRepository.getById(chatId) ?: return
    if (!chat.isGroup) return
    notifyGroupRevisionChangedWithData(
        json = json,
        chatId = chatId,
        reason = reason,
        actorId = actorId,
        targetUserId = targetUserId,
        memberRevision = chat.memberRevision,
        recipientIds = recipientIds ?: participantRepository.participantIds(chatId)
    )
}

/** 批量快照已就绪时的广播入口（避免逐群 getChatById + getParticipantIds）。 */
internal suspend fun notifyGroupRevisionChangedWithData(
    json: Json,
    chatId: String,
    reason: String,
    actorId: String,
    targetUserId: String? = null,
    memberRevision: Long,
    recipientIds: List<String>
) {
    val payload = GroupRevisionChangedPayload(
        chatId = chatId,
        memberRevision = memberRevision,
        reason = reason,
        actorId = actorId,
        targetUserId = targetUserId
    )
    val message = json.encodeToString(
        WsMessage.serializer(),
        WsMessage("GROUP_REVISION_CHANGED", json.encodeToString(GroupRevisionChangedPayload.serializer(), payload))
    )
    val recipients = recipientIds.distinct()
    recipients.forEach { sendToUser(it, message) }
}

internal suspend fun ApplicationCall.respondBotUnavailable() {
    respond(HttpStatusCode.Forbidden, ErrorResponse("bot unavailable or disabled", code = "BOT_UNAVAILABLE"))
}

/** 9.3xx：群邀请事件（CREATED/ACCEPTED/DECLINED/CANCELLED）实时推送；CREATED 额外 FCM 唤醒离线被邀请人。 */
internal suspend fun notifyGroupInvite(
    json: Json,
    invite: GroupInvitationDto,
    action: String,
    pushService: FcmPushService
) {
    val payload = json.encodeToString(
        GroupInviteEventPayload.serializer(),
        GroupInviteEventPayload(action = action, invite = invite)
    )
    val envelope = json.encodeToString(WsMessage.serializer(), WsMessage("GROUP_INVITE", payload))
    // 目标用户实时感知邀请；邀请人/管理员侧同步状态（撤销/拒绝）
    sendToUser(invite.userId, envelope)
    if (invite.inviterId.isNotBlank() && invite.inviterId != invite.userId) {
        sendToUser(invite.inviterId, envelope)
    }
    if (action == "CREATED") {
        pushService.enqueueGroupInvite(
            recipientId = invite.userId,
            fromUserId = invite.inviterId,
            inviteId = invite.id,
            chatId = invite.chatId,
            action = action
        )
    }
}

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
) {
    if (attributes.contains(RoutingInstalledKey)) {
        if (attributes[RoutingPushServiceKey] !== pushService) pushService.shutdown()
        return
    }
    attributes.put(RoutingInstalledKey, Unit)
    attributes.put(RoutingPushServiceKey, pushService)
    val signalKeyRepo = SignalKeyRepository()
    val turnCredentialService = TurnCredentialService(
        turnUrls = ServerConfig.turnUrls,
        sharedSecret = ServerConfig.turnSharedSecret,
        ttlSeconds = ServerConfig.turnCredentialTtlSeconds
    )
    val starMessageRepo = StarMessageRepository()
    val pinnedMessageRepo = PinnedMessageRepository()
    val serviceMessageRepo = ServiceMessageRepository(messagingV2Repository)
    val authTokenRepo = AuthTokenRepository()
    val friendRepo = FriendRepository()
    val chatFolderRepo = ChatFolderRepository()
    val clientPrefsRepo = ClientPrefsRepository()
    val aiRepo = AiRepository()
    val encryptedAttachmentRepo = EncryptedAttachmentRepository()
    val senderKeyDistributionRepo = SenderKeyDistributionRepository()
    val reportRepo = ReportRepository()
    val groupMembershipRepo = GroupMembershipRepository()
    val groupMembershipService = GroupMembershipService(groupMembershipRepo)
    val groupProfileRepo = GroupProfileRepository()
    val groupModerationRepo = GroupModerationRepository()
    val groupInvitationRepo = GroupInvitationRepository()
    val groupInvitationService = GroupInvitationService(groupInvitationRepo)
    val conversationLifecycleRepo = ConversationLifecycleRepository()
    val conversationCreationRepo = ConversationCreationRepository()
    val conversationCreationService = ConversationCreationService(
        conversationCreationRepo,
        groupInvitationService,
    )
    val conversationSettingsRepo = ConversationSettingsRepository()
    val conversationParticipantRepo = ConversationParticipantRepository()
    val conversationQueryRepo = ConversationQueryRepository()
    val groupAuditRepo = GroupAuditRepository()
    val groupMediaReferenceRepo = GroupMediaReferenceRepository()
    val aiSummaryCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    aiSummaryCleanupScope.launch {
        while (isActive) {
            runCatching {
                val now = System.currentTimeMillis()
                encryptedAttachmentRepo.deleteExpired(now).forEach(EncryptedAttachmentStorage::delete)
                EncryptedAttachmentStorage.deleteStaleFiles(
                    validIds = encryptedAttachmentRepo.allIds(),
                    olderThan = now - ATTACHMENT_UPLOAD_TTL_MS
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.warn("Encrypted attachment cleanup failed", error)
            }
            runCatching {
                val olderThan = System.currentTimeMillis() - MEDIA_ORPHAN_GRACE_MS
                postRepo.deleteStaleUnreferencedImages(olderThan)
                com.maodouchat.server.service.FileStorageService.deleteStaleGroupAvatars(
                    validFilenames = groupMediaReferenceRepo.allReferencedAvatarFilenames(),
                    olderThan = olderThan
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.warn("Media orphan cleanup failed", error)
            }
            runCatching { aiRepo.purgeOldAuditLogs() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.warn("AI audit log purge failed", error)
                }
            runCatching { purgeAdminOperationalData() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.warn("Admin operational data purge failed", error)
                }
            runCatching { friendRepo.expireStalePending() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.warn("Stale friend request expiry failed", error)
                }
            runCatching { GroupCheckinRepository.purgeOldData() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.warn("Group play data purge failed", error)
                }
            runCatching { groupAuditRepo.purgeOlderThan() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.warn("Group audit log purge failed", error)
                }
            runCatching { BotRepository.purgeOldCommandLogs() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.warn("Bot command log purge failed", error)
                }
            runCatching { BotRepository.purgeOldInbox() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.warn("Bot inbox purge failed", error)
                }
            runCatching { signalKeyRepo.purgeConsumedPreKeys() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.warn("Consumed prekey purge failed", error)
                }
            // 1.81：清理已删除评论的残留点赞
            runCatching { postRepo.purgeOrphanedCommentLikes() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.warn("Orphaned comment like purge failed", error)
                }
            runCatching { reportRepo.purgeResolvedOlderThan() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.warn("Resolved report purge failed", error)
                }
            delay(6L * 60L * 60L * 1_000L)
        }
    }
    aiSummaryCleanupScope.launch {
        while (isActive) {
            runCatching { authTokenRepo.deleteExpired() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.warn("Expired authentication session cleanup failed", error)
                }
            delay(15L * 60L * 1_000L)
        }
    }
    val (cacheService, cacheLifecycleId) =
        com.maodouchat.server.service.CacheService.acquireLifecycle()
    val webhookLifecycleId = com.maodouchat.server.service.BotWebhookService.start()
    environment.monitor.subscribe(ApplicationStopped) {
        aiSummaryCleanupScope.cancel()
        com.maodouchat.server.service.BotWebhookService.shutdown(webhookLifecycleId)
        cacheService.shutdown(cacheLifecycleId)
        pushService.shutdown()
    }
    val moderationRuleRepo = ModerationRuleRepository()
    val nearbyRepo = NearbyRepository()
    val preKeyFetchTracker = BoundedRateLimiter(maxBuckets = 20_000)
    // 邮箱验证码发送频率限制：每邮箱每分钟最多 3 次
    val sendCodeRateLimiter = BoundedRateLimiter()
    // 邮箱验证码发送 IP 级限制：每 IP 每分钟最多 20 次（防邮件轰炸）
    val sendCodeIpRateLimiter = BoundedRateLimiter()
    // 登录+注册 IP 级限制：每 IP 每分钟最多 10 次（防暴破）
    val loginIpRateLimiter = BoundedRateLimiter()
    // 登录/注册/重置 按账号(email)限制：关闭「多源 IP 分布式爆破同一账号」的绕过路径
    val loginEmailRateLimiter = BoundedRateLimiter()
    // Single-account consecutive login failure lockout: 5 failures lock the account for 15 minutes.
    val loginLockouts = ConcurrentHashMap<String, LoginLockout>()
    val loginLockoutSweepAt = java.util.concurrent.atomic.AtomicLong(0L)
    val LOGIN_MAX_FAILS = 5
    val LOGIN_LOCK_MS = 15L * 60L * 1000L

    /** 周期清理过期锁定条目，避免内存无界增长。 */
    fun sweepLoginLockouts(now: Long = System.currentTimeMillis()) {
        val lastSweep = loginLockoutSweepAt.get()
        if (now - lastSweep > 60_000L && loginLockoutSweepAt.compareAndSet(lastSweep, now)) {
            val staleCutoff = now - LOGIN_LOCK_MS - 60_000L
            loginLockouts.entries.removeIf { it.value.lastFailureAt <= staleCutoff }
        }
    }

    /** 记录一次登录失败；达到阈值则锁定「该账号 + 该源 IP」15 分钟。成功登录后由调用方 remove。
     *  8.51 修复 M1：锁定 key 加入源 IP——攻击者源 IP 的失败只锁该 IP 与账号的组合，
     *  受害者从自己 IP 登录不受远程锁定影响（可用性 DoS 缓解）。 */
    fun recordLoginFailure(emailKey: String, ip: String) {
        val now = System.currentTimeMillis()
        sweepLoginLockouts(now)
        loginLockouts.compute("$emailKey|$ip") { _, existing ->
            val lock = existing ?: LoginLockout(0, 0L, now)
            lock.lastFailureAt = now
            lock.fails += 1
            if (lock.fails >= LOGIN_MAX_FAILS) {
                lock.lockUntil = now + LOGIN_LOCK_MS
                // 8.31 运维修复 HIGH：账号锁定是安全事件，必须留应用日志（此前仅内存计数）
                org.slf4j.LoggerFactory.getLogger("LoginSecurity")
                    .warn("Login account locked [emailKey={} ip={}] after {} failures for {}ms", emailKey, ip, LOGIN_MAX_FAILS, LOGIN_LOCK_MS)
            }
            lock
        }
    }
    // 好友申请按发起用户限流：防止单用户向同/多目标狂发申请（通知轰炸/骚扰）
    val friendRequestRateLimiter = BoundedRateLimiter()
    // 用户目录查询按用户限流：防止整库抓取/枚举（/api/users 空 q 返回全量、/api/users/search 可遍历）
    val userSearchRateLimiter = BoundedRateLimiter()
    // 全局消息搜索按用户限流：底层 LIKE 全表扫描成本高，10/min 防止搜索 DoS
    // 0.98：公开主页接口按 IP 限流（/api/public/profile、/u/{username} 匿名可被枚举）
    val publicProfileRateLimiter = BoundedRateLimiter()
    // 附近的人更新/查询按用户限流：10/min 更新 + 30/min 查询，防高频 DB 写与 haversine 计算
    val nearbyUpdateRateLimiter = BoundedRateLimiter()
    val nearbyQueryRateLimiter = BoundedRateLimiter()
    // 表情回应按用户限流：每次请求全群 fanout，60/min 防帧风暴
    val reactionRateLimiter = BoundedRateLimiter()
    // bot 创建/轮换 token 按用户限流 + bot 发消息按 bot 限流：防 churn 与 fanout 风暴
    val botCreateRateLimiter = BoundedRateLimiter()
    val botTokenRateLimiter = BoundedRateLimiter()
    val botSendRateLimiter = BoundedRateLimiter()
    val aiRateLimiter = BoundedRateLimiter()
    // 举报提交频率限制：每用户每分钟最多 5 次，防止刷爆审核队列（骚扰/审核资源耗尽）
    val reportRateLimiter = BoundedRateLimiter()
    // 头像上传频率限制：每用户每分钟最多 10 次，防止头像对象高频 churn / 存储放大
    val avatarRateLimiter = BoundedRateLimiter()
    // 创建聊天频率限制：每用户每分钟最多 20 次，防止群 spam / 聊天枚举
    val createChatRateLimiter = BoundedRateLimiter()
    // 动态发布频率限制：每用户每分钟最多 20 次，防止 feed spam / 存储放大
    val postRateLimiter = BoundedRateLimiter()
    // 动态图片上传频率限制：每用户每分钟最多 10 次，防止存储放大（与头像同量级）
    val postImageRateLimiter = BoundedRateLimiter()
    // 动态评论频率限制：每用户每分钟最多 30 次，防止评论洪水 / 通知轰炸作者
    val commentRateLimiter = BoundedRateLimiter()
    val postLikeRateLimiter = BoundedRateLimiter()
    /** 1.83：评论点赞独立限流（与动态点赞预算隔离）。 */
    val commentLikeRateLimiter = BoundedRateLimiter()
    val totpManageRateLimiter = BoundedRateLimiter()
    // 1-on-1 聊天创建锁：防止同一对用户并发创建多个聊天
    val json = Json { ignoreUnknownKeys = true }

    // 管理后台路由（/admin/*）—— 首个「明确未完成」模块落地
    configureAdminRouting(userRepo, postRepo, moderationRuleRepo, reportRepo)

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
                    path.matches(Regex("^/api/attachments/[^/]+/chunks?$"))
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
        configureHealthRoutes()
        configureConversationSettingsRoutes(
            userRepo = userRepo,
            settingsRepository = conversationSettingsRepo,
            participantRepository = conversationParticipantRepo,
            json = json,
        )
        configureConversationRoutes(
            userRepo = userRepo,
            creationService = conversationCreationService,
            queryRepository = conversationQueryRepo,
            invitationService = groupInvitationService,
            lifecycleRepository = conversationLifecycleRepo,
            pushService = pushService,
            createRateLimiter = createChatRateLimiter,
            json = json,
        )
        configureGroupInvitationRoutes(
            userRepo = userRepo,
            membershipService = groupMembershipService,
            invitationService = groupInvitationService,
            queryRepository = conversationQueryRepo,
            participantRepository = conversationParticipantRepo,
            pushService = pushService,
            json = json,
        )
        configureFriendRoutes(
            userRepository = userRepo,
            friendRepository = friendRepo,
            pushService = pushService,
            requestRateLimiter = friendRequestRateLimiter,
            json = json,
        )
        configureClientSyncRoutes(
            chatFolderRepository = chatFolderRepo,
            clientPrefsRepository = clientPrefsRepo,
        )
        configureGroupAdministrationRoutes(
            userRepo = userRepo,
            membershipService = groupMembershipService,
            profileRepository = groupProfileRepo,
            moderationRepository = groupModerationRepo,
            invitationService = groupInvitationService,
            queryRepository = conversationQueryRepo,
            participantRepository = conversationParticipantRepo,
            auditRepository = groupAuditRepo,
            signalKeyRepository = signalKeyRepo,
            senderKeyRepository = senderKeyDistributionRepo,
            avatarRateLimiter = avatarRateLimiter,
            json = json,
        )
        configureSignalKeyRoutes(
            signalKeyRepository = signalKeyRepo,
            conversationQueryRepository = conversationQueryRepo,
            preKeyFetchLimiter = preKeyFetchTracker,
        )
        configureCallSignalingRoutes(
            userRepository = userRepo,
            conversationQueryRepository = conversationQueryRepo,
            signalingRepository = signalingRepo,
            callInviteRateLimiter = callInviteRateLimiter,
            turnCredentialService = turnCredentialService,
            pushService = pushService,
            json = json,
        )
        configureBotProbeRoutes(botSendRateLimiter)
        configureBotRuntimeFlagRoutes(botSendRateLimiter)
        configureBotHintRoutes(
            userRepository = userRepo,
            participantRepository = conversationParticipantRepo,
            serviceMessageRepository = serviceMessageRepo,
            botRateLimiter = botSendRateLimiter,
            json = json,
        )
        configureBotApiRoutes(
            userRepo = userRepo,
            starMessageRepo = starMessageRepo,
            pinnedMessageRepo = pinnedMessageRepo,
            serviceMessageRepo = serviceMessageRepo,
            groupMembershipService = groupMembershipService,
            groupProfileRepo = groupProfileRepo,
            groupModerationRepo = groupModerationRepo,
            groupInvitationService = groupInvitationService,
            conversationLifecycleRepo = conversationLifecycleRepo,
            conversationParticipantRepo = conversationParticipantRepo,
            conversationQueryRepo = conversationQueryRepo,
            botSendRateLimiter = botSendRateLimiter,
            json = json,
            messagingV2Repository = messagingV2Repository,
        )

        // Authentication and public identity routes.
        configurePublicUpdateRoutes(cacheService)
        configurePublicProfileRoutes(
            userRepository = userRepo,
            cacheService = cacheService,
            rateLimiter = publicProfileRateLimiter,
        )
        configureAuthRoutes(
            userRepo = userRepo,
            authTokenRepo = authTokenRepo,
            pushTokenRepo = pushTokenRepo,
            loginIpRateLimiter = loginIpRateLimiter,
            loginEmailRateLimiter = loginEmailRateLimiter,
            sendCodeRateLimiter = sendCodeRateLimiter,
            sendCodeIpRateLimiter = sendCodeIpRateLimiter,
            loginLockouts = loginLockouts,
            sweepLoginLockouts = ::sweepLoginLockouts,
            recordLoginFailure = ::recordLoginFailure,
        )

        // ─── 官网静态页面（无需认证） ─────────────

        configurePublicSiteRoutes()
configureEncryptedAttachmentRoutes(
            userRepo = userRepo,
            encryptedAttachmentRepo = encryptedAttachmentRepo,
            conversationParticipantRepo = conversationParticipantRepo,
            conversationQueryRepo = conversationQueryRepo,
            rateLimiter = aiRateLimiter,
            messagingV2Repository = messagingV2Repository,
        )
        configureAuthenticatedSessionRoutes(
            userRepo = userRepo,
            mfaService = com.maodouchat.server.service.MfaService(),
            authTokenRepo = authTokenRepo,
            pushTokenRepo = pushTokenRepo,
            totpManageRateLimiter = totpManageRateLimiter,
        )
        configureAccountRoutes(
            userRepo = userRepo,
            postRepo = postRepo,
            authTokenRepo = authTokenRepo,
            pushTokenRepo = pushTokenRepo,
            notificationPreferenceRepo = notificationPreferenceRepo,
            nearbyRepo = nearbyRepo,
            cacheService = cacheService,
            groupMediaReferenceRepo = groupMediaReferenceRepo,
            encryptedAttachmentRepo = encryptedAttachmentRepo,
            conversationParticipantRepo = conversationParticipantRepo,
            conversationQueryRepo = conversationQueryRepo,
            userSearchRateLimiter = userSearchRateLimiter,
            nearbyUpdateRateLimiter = nearbyUpdateRateLimiter,
            nearbyQueryRateLimiter = nearbyQueryRateLimiter,
            avatarRateLimiter = avatarRateLimiter,
            json = json,
        )
        configurePollLegacyRoutes(
            userRepo = userRepo,
            conversationParticipantRepo = conversationParticipantRepo,
            conversationQueryRepo = conversationQueryRepo,
            json = json,
        )
        authenticate("auth-jwt") {
            configureBotInteractionRoutes(
                userRepo = userRepo,
                conversationParticipantRepo = conversationParticipantRepo,
                conversationCreationRepo = conversationCreationRepo,
                conversationQueryRepo = conversationQueryRepo,
                groupMembershipService = groupMembershipService,
                botCreateRateLimiter = botCreateRateLimiter,
                createChatRateLimiter = createChatRateLimiter,
                json = json,
            )

            configureBotManagementRoutes(
                userRepo = userRepo,
                conversationParticipantRepo = conversationParticipantRepo,
                botCreateRateLimiter = botCreateRateLimiter,
                botTokenRateLimiter = botTokenRateLimiter,
                json = json,
            )

        }
        configureReportModerationRoutes(
            userRepo = userRepo,
            postRepo = postRepo,
            reportRepo = reportRepo,
            moderationRuleRepo = moderationRuleRepo,
            authTokenRepo = authTokenRepo,
            pushTokenRepo = pushTokenRepo,
            conversationParticipantRepo = conversationParticipantRepo,
            reportRateLimiter = reportRateLimiter,
            json = json,
            messagingV2Repository = messagingV2Repository,
        )
        configureSocialPostRoutes(
            userRepo = userRepo,
            postRepo = postRepo,
            moderationRuleRepo = moderationRuleRepo,
            aiGateway = aiGateway,
            pushService = pushService,
            conversationParticipantRepo = conversationParticipantRepo,
            conversationQueryRepo = conversationQueryRepo,
            starMessageRepo = starMessageRepo,
            pinnedMessageRepo = pinnedMessageRepo,
            postRateLimiter = postRateLimiter,
            postImageRateLimiter = postImageRateLimiter,
            commentRateLimiter = commentRateLimiter,
            postLikeRateLimiter = postLikeRateLimiter,
            commentLikeRateLimiter = commentLikeRateLimiter,
            json = json,
        )
    }
}

@kotlinx.serialization.Serializable
internal data class PinnedMessagesUpdatedPayload(
    val chatId: String,
    val actorId: String,
    val pins: List<PinnedMessageResponse>
)
