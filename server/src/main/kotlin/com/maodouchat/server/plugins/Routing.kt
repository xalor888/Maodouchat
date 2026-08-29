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
import java.io.ByteArrayInputStream
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
private val routingJson = Json { ignoreUnknownKeys = true }

private fun loadPublicHtml(page: String): String? =
    Thread.currentThread().contextClassLoader?.getResource("public/$page.html")?.readText()
        ?: object {}.javaClass.classLoader.getResource("public/$page.html")?.readText()

private suspend fun ApplicationCall.respondPublicHtml(page: String, fallback: String = "<h1>毛豆聊天</h1>") {
    response.header(HttpHeaders.CacheControl, "no-cache, must-revalidate")
    respondText(loadPublicHtml(page) ?: fallback, ContentType.Text.Html)
}

private val routingParseLogger = org.slf4j.LoggerFactory.getLogger("RoutingParse")
private val loginAuditLogger = org.slf4j.LoggerFactory.getLogger("LoginAudit")

// 手动 JSON 解析 —— 绕过 Ktor ContentNegotiation 对 receiveNullable / ContentConversion 的歧义。
// 在 Ktor 2.3 + in-memory testApplication 同进程多次 mount 时行为最稳定。
// 用法：val req = call.receiveBoundedText()?.let { parseJson<SomeRequest>(it) }
internal inline fun <reified T> parseJson(text: String): T? = try {
    if (text.isBlank()) null
    else routingJson.decodeFromString<T>(text)
} catch (e: Exception) {
    // 9.4xx：不再静默吞掉解析失败——记录类型与错误摘要（正文截断，避免日志膨胀/泄密）
    routingParseLogger.warn(
        "JSON parse failed for {}: {} (body head: {})",
        T::class.simpleName,
        e.message.orEmpty(),
        text.take(200).replace('\n', ' ')
    )
    null
}

internal suspend fun ApplicationCall.receiveBoundedText(maxChars: Int = MAX_JSON_BODY_CHARS): String? {
    // 9.135：字节预算 = 字符预算 × 4（UTF-8 单字符最多 4 字节）。此前按 maxChars 字节截断，
    // 中文等多字节正文在接近字符上限时被提前拒绝（字节数天然大于字符数）；字符数检查才是语义上限。
    val maxBytes = maxChars.toLong() * 4
    val declaredLength = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (declaredLength != null && declaredLength > maxBytes) return null
    val channel = receiveChannel()
    val output = java.io.ByteArrayOutputStream(minOf(maxChars, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read < 0) break
        if (read == 0) {
            // 9.150：readAvailable 无数据时立即返回 0，直接 continue 会空转烧 CPU
            //（慢速/恶意客户端逐字节送包时尤其明显）；挂起等待有数据或 EOF 再继续
            channel.awaitContent()
            continue
        }
        if (total + read > maxBytes) return null
        output.write(buffer, 0, read)
        total += read
    }
    return String(output.toByteArray(), Charsets.UTF_8).takeIf { it.length <= maxChars }
}

internal suspend fun ApplicationCall.receiveBoundedTextOrEmpty(maxChars: Int = MAX_JSON_BODY_CHARS): String {
    return try {
        receiveBoundedText(maxChars) ?: ""
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 9.4xx：记录读取失败（此前静默返回空串，掩盖网络错误/超时）
        routingParseLogger.warn(
            "Body read failed on {} {}: {}",
            request.httpMethod.value,
            request.path(),
            e.message.orEmpty()
        )
        ""
    }
}

/** MASTER_ADMINS inherit the limited content-review permissions even when isModerator is false. */

internal suspend fun ApplicationCall.rejectIfSuspended(userRepo: UserRepository, userId: String): Boolean {
    val until = userRepo.getSuspendedUntil(userId)
    if (until <= 0L) return false
    respond(HttpStatusCode.Forbidden, ErrorResponse(restrictionMessage(until, "账号已被临时封禁")))
    return true
}

internal suspend fun ApplicationCall.rejectIfMaintenance(messageId: String? = null): Boolean {
    if (!RuntimeConfigService.isMaintenanceMode()) return false
    respond(
        HttpStatusCode.ServiceUnavailable,
        ErrorResponse(
            error = RuntimeConfigService.get(RuntimeConfigService.KEY_MAINTENANCE_MESSAGE).ifBlank {
                "System under maintenance"
            },
            code = "MAINTENANCE",
            messageId = messageId,
        )
    )
    return true
}

internal suspend fun ApplicationCall.rejectIfMessageRestricted(userRepo: UserRepository, userId: String): Boolean {
    if (rejectIfSuspended(userRepo, userId)) return true
    val until = userRepo.getMessageRestrictionUntil(userId)
    if (until <= 0L) return false
    respond(HttpStatusCode.Forbidden, ErrorResponse(restrictionMessage(until, "你已被限制发消息")))
    return true
}

internal suspend fun ApplicationCall.rejectIfPostRestricted(userRepo: UserRepository, userId: String): Boolean {
    if (rejectIfSuspended(userRepo, userId)) return true
    val until = userRepo.getPostRestrictionUntil(userId)
    if (until <= 0L) return false
    respond(HttpStatusCode.Forbidden, ErrorResponse(restrictionMessage(until, "你已被限制发布动态")))
    return true
}

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
    callInviteRateLimiter: CallInviteRateLimiter = CallInviteRateLimiter()
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
    val serviceMessageRepo = ServiceMessageRepository()
    val authTokenRepo = AuthTokenRepository()
    val friendRepo = FriendRepository()
    val chatFolderRepo = ChatFolderRepository()
    val clientPrefsRepo = ClientPrefsRepository()
    val aiRepo = AiRepository()
    val encryptedAttachmentRepo = EncryptedAttachmentRepository()
    val senderKeyDistributionRepo = SenderKeyDistributionRepository()
    val reportRepo = ReportRepository()
    val groupMembershipRepo = GroupMembershipRepository()
    val groupLifecycleService = GroupLifecycleService(groupMembershipRepo)
    val groupProfileRepo = GroupProfileRepository()
    val groupModerationRepo = GroupModerationRepository()
    val groupInvitationRepo = GroupInvitationRepository()
    val conversationLifecycleRepo = ConversationLifecycleRepository()
    val conversationCreationRepo = ConversationCreationRepository()
    val conversationCreationService = ConversationCreationService(
        conversationCreationRepo,
        groupInvitationRepo,
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
            invitationRepository = groupInvitationRepo,
            lifecycleRepository = conversationLifecycleRepo,
            pushService = pushService,
            createRateLimiter = createChatRateLimiter,
            json = json,
        )
        configureGroupInvitationRoutes(
            userRepo = userRepo,
            membershipRepository = groupMembershipRepo,
            invitationRepository = groupInvitationRepo,
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
            lifecycleService = groupLifecycleService,
            profileRepository = groupProfileRepo,
            moderationRepository = groupModerationRepo,
            invitationRepository = groupInvitationRepo,
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
            groupMembershipRepo = groupMembershipRepo,
            groupLifecycleService = groupLifecycleService,
            groupProfileRepo = groupProfileRepo,
            groupModerationRepo = groupModerationRepo,
            groupInvitationRepo = groupInvitationRepo,
            conversationLifecycleRepo = conversationLifecycleRepo,
            conversationParticipantRepo = conversationParticipantRepo,
            conversationQueryRepo = conversationQueryRepo,
            botSendRateLimiter = botSendRateLimiter,
            json = json,
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
        )
        configureAuthenticatedSessionRoutes(
            userRepo = userRepo,
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
            get("/api/chats/{chatId}/bot-commands") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val chatId = call.parameters["chatId"]!!
                if (!conversationParticipantRepo.isParticipant(chatId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该聊天"))
                    return@get
                }
                call.respond(
                    buildJsonObject {
                        put("ok", true)
                        putJsonArray("bots") {
                            com.maodouchat.server.repository.BotRepository.listEnabledBotsInChat(chatId).forEach { bot ->
                                add(
                                    buildJsonObject {
                                        put("id", bot.id)
                                        put("username", bot.username)
                                        put("name", bot.name)
                                    }
                                )
                            }
                        }
                        putJsonArray("commands") {
                            com.maodouchat.server.repository.BotRepository.listCommandsForChat(chatId).forEach { item ->
                                add(
                                    buildJsonObject {
                                        put("botId", item.botId)
                                        put("username", item.username)
                                        put("name", item.name)
                                        put("command", item.command)
                                        put("description", item.description)
                                    }
                                )
                            }
                        }
                    }
                )
            }

            post("/api/chats/{chatId}/bot-inbox") {
                if (call.rejectIfMaintenance()) return@post
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfSuspended(userRepo, userId)) return@post
                if (call.rejectIfMessageRestricted(userRepo, userId)) return@post
                if (!RuntimeConfigService.isBotsAllowed()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot platform disabled"))
                    return@post
                }
                val chatId = call.parameters["chatId"]!!
                if (!conversationParticipantRepo.isParticipant(chatId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该聊天"))
                    return@post
                }
                if (!botCreateRateLimiter.acquire("bot-inbox:$userId", maxPerMinute = 60)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                    return@post
                }
                val body = call.receiveBoundedTextOrEmpty(8_192)
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val text = (obj["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val botIdHint = (obj["botId"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                val cleaned = com.maodouchat.server.bot.BotCommandPolicy.sanitizeInboxText(text)
                if (cleaned == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("命令无效或不能是密文"))
                    return@post
                }
                val delivered = com.maodouchat.server.repository.BotRepository.enqueueUserCommand(
                    chatId = chatId,
                    userId = userId,
                    text = cleaned,
                    botIdHint = botIdHint
                )
                if (delivered.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("该会话没有可接收命令的机器人"))
                    return@post
                }
                delivered.forEach { (botId, payload) ->
                    try {
                        com.maodouchat.server.service.BotWebhookService.notifyBotDirect(
                            botId = botId,
                            bodyJson = payload
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
                call.respond(
                    buildJsonObject {
                        put("ok", true)
                        put("delivered", delivered.size)
                    }
                )
            }

            post("/api/bots/{botId}/dm") {
                if (call.rejectIfMaintenance()) return@post
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfSuspended(userRepo, userId)) return@post
                if (!RuntimeConfigService.isBotsAllowed()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot platform disabled"))
                    return@post
                }
                val botId = call.parameters["botId"]!!
                val bot = com.maodouchat.server.repository.BotRepository.get(botId)
                if (bot == null || !bot.enabled) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found"))
                    return@post
                }
                if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(botId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot unavailable"))
                    return@post
                }
                if (!createChatRateLimiter.acquire(userId, maxPerMinute = 20)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("创建会话过于频繁，请稍后再试"))
                    return@post
                }
                val created = try {
                    conversationCreationRepo.getOrCreateDirect(userId, botId)
                } catch (_: IllegalArgumentException) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无法与该机器人创建私聊"))
                    return@post
                }
                val chat = conversationQueryRepo.getById(created.id, userId)
                if (chat == null) {
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("会话创建成功但读取失败，请刷新"))
                    return@post
                }
                call.respond(HttpStatusCode.Created, chat)
            }

            post("/api/chats/{chatId}/bot-callback") {
                if (call.rejectIfMaintenance()) return@post
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // 8.33 修复：封禁用户不得触发 bot 回调（bot 平台交互面一致收口）
                if (call.rejectIfSuspended(userRepo, userId)) return@post
                val chatId = call.parameters["chatId"]!!
                if (!conversationParticipantRepo.isParticipant(chatId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该聊天"))
                    return@post
                }
                val body = call.receiveBoundedTextOrEmpty(16_384)
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
                val botUserId = obj["botUserId"]?.jsonPrimitive?.content.orEmpty()
                val callbackData = obj["callbackData"]?.jsonPrimitive?.content.orEmpty()
                if (messageId.isBlank() || messageId.length > 80 ||
                    botUserId.isBlank() || botUserId.length > 80 ||
                    callbackData.isBlank() || callbackData.length > 128
                ) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("messageId/botUserId/callbackData required"))
                }
                val bot = com.maodouchat.server.repository.BotRepository.get(botUserId)
                if (bot == null || !bot.enabled) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot unavailable"))
                }
                val updateId = "cbq_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
                val payload = kotlinx.serialization.json.buildJsonObject {
                    put("update_type", kotlinx.serialization.json.JsonPrimitive("callback_query"))
                    put("callback_query", kotlinx.serialization.json.buildJsonObject {
                        put("id", kotlinx.serialization.json.JsonPrimitive(updateId))
                        put("from", kotlinx.serialization.json.JsonPrimitive(userId))
                        put("chatId", kotlinx.serialization.json.JsonPrimitive(chatId))
                        put("messageId", kotlinx.serialization.json.JsonPrimitive(messageId))
                        put("data", kotlinx.serialization.json.JsonPrimitive(callbackData))
                    })
                }.toString()
                if (!com.maodouchat.server.repository.BotRepository.enqueueCallbackIfAuthorized(
                        chatId = chatId,
                        userId = userId,
                        botId = bot.id,
                        messageId = messageId,
                        callbackData = callbackData,
                        updateJson = payload
                    )
                ) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("回调按钮无效或已不可用"))
                }
                // Targeted webhook for this bot only (avoid fan-out double enqueue).
                try {
                    com.maodouchat.server.service.BotWebhookService.notifyBotDirect(
                        botId = bot.id,
                        bodyJson = payload
                    )
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                call.respond(
                buildJsonObject {
put("ok", true)
put("callbackQueryId", updateId)
                }
            )
            }

post("/api/chats/{chatId}/bots") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfMaintenance()) return@post
                if (!RuntimeConfigService.isBotsAllowed()) {
                    // 8.32 一致性：功能禁用统一 403（与 nearby/posts/chat_folders 等 disabled 语义一致）
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot platform disabled"))
                }
                if (call.rejectIfSuspended(userRepo, userId)) return@post
                val chatId = call.parameters["chatId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing chatId"))
                val body = call.receiveBoundedTextOrEmpty(4_096)
                val botId = runCatching { Json.parseToJsonElement(body).jsonObject["botId"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
                if (botId.isBlank() || botId.length > 80) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("botId required"))
                }
                val addResult = groupMembershipRepo.addOwnedBot(chatId, userId, botId, maxGroupMembers())
                when (addResult) {
                    AddOwnedBotResult.ADDED ->
                        notifyGroupRevisionChanged(conversationQueryRepo, conversationParticipantRepo, json, chatId, "BOT_ADDED", userId, botId)
                    AddOwnedBotResult.ALREADY_MEMBER -> Unit
                    AddOwnedBotResult.CHAT_NOT_FOUND ->
                        return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("群聊不存在"))
                    AddOwnedBotResult.NOT_GROUP ->
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("只能向群聊邀请机器人"))
                    AddOwnedBotResult.FORBIDDEN ->
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("仅群主或管理员可邀请机器人"))
                    AddOwnedBotResult.BOT_NOT_FOUND ->
                        return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found"))
                    AddOwnedBotResult.BOT_NOT_OWNED ->
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("只能邀请自己的机器人"))
                    AddOwnedBotResult.BOT_DISABLED ->
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot disabled"))
                    AddOwnedBotResult.MEMBER_LIMIT_EXCEEDED ->
                        return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("群成员已达上限"))
                }
                val bot = com.maodouchat.server.repository.BotRepository.get(botId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("bot not found"))
                if (addResult == AddOwnedBotResult.ADDED) {
                    com.maodouchat.server.service.BotWebhookService.notifyChatEvent(
                        chatId = chatId,
                        event = "bot_added",
                        senderId = userId,
                        type = "SYSTEM",
                        textPreview = "bot ${bot.username} added"
                    )
                }
                call.respond(
                buildJsonObject {
put("ok", true)
put("botId", botId)
                }
            )
            }

get("/api/bots") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                call.respond(com.maodouchat.server.repository.BotRepository.listByOwner(userId))
            }
            post("/api/bots") {
                if (call.rejectIfMaintenance()) return@post
                if (!RuntimeConfigService.isBotsAllowed()) {
                    // 8.32 一致性：功能禁用统一 403（与 nearby/posts/chat_folders 等 disabled 语义一致）
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot platform disabled"))
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfSuspended(userRepo, userId)) return@post
                // 创建限流：防 create-delete churn 刷 DB（maxBotsPerUser 语义可被绕过）
                if (!botCreateRateLimiter.acquire(userId, maxPerMinute = 5)) {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("创建机器人太频繁，请稍后再试"))
                }
                val body = call.receiveBoundedTextOrEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
                val username = obj["username"]?.jsonPrimitive?.content.orEmpty()
                val description = obj["description"]?.jsonPrimitive?.content
                when (val result = com.maodouchat.server.repository.BotRepository.create(userId, name, username, description)) {
                    is com.maodouchat.server.repository.BotRepository.BotCreateResult.Success ->
                        call.respond(result.bot)
                    com.maodouchat.server.repository.BotRepository.BotCreateResult.UsernameTaken ->
                        call.respond(HttpStatusCode.Conflict, ErrorResponse("机器人用户名已被占用"))
                    com.maodouchat.server.repository.BotRepository.BotCreateResult.MaxBotsReached ->
                        call.respond(HttpStatusCode.Conflict, ErrorResponse("机器人数量已达上限"))
                    com.maodouchat.server.repository.BotRepository.BotCreateResult.InvalidInput ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("创建机器人失败（用户名非法）"))
                    com.maodouchat.server.repository.BotRepository.BotCreateResult.OwnerInvalid ->
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("账号状态不可用"))
                }
            }
            post("/api/bots/{botId}/token") {
                if (call.rejectIfMaintenance()) return@post
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfSuspended(userRepo, userId)) return@post
                // token 轮换限流：防高频轮换刷 DB 写
                if (!botTokenRateLimiter.acquire(userId, maxPerMinute = 10)) {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作太频繁，请稍后再试"))
                }
                val botId = call.parameters["botId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing botId"))
                val bot = com.maodouchat.server.repository.BotRepository.regenerateToken(botId, userId)
                    ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
                call.respond(bot)
            }
            put("/api/bots/{botId}/webhook") {
                if (call.rejectIfMaintenance()) return@put
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfSuspended(userRepo, userId)) return@put
                val botId = call.parameters["botId"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing botId"))
                val body = call.receiveBoundedTextOrEmpty()
                val url = runCatching { Json.parseToJsonElement(body).jsonObject["url"]?.jsonPrimitive?.content }
                    .getOrNull()?.trim()?.take(500)
                if (!url.isNullOrBlank() && !com.maodouchat.server.repository.BotRepository.isAllowedWebhookUrl(url)) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("webhook 无效"))
                }
                val bot = com.maodouchat.server.repository.BotRepository.setWebhook(botId, userId, url)
                    ?: return@put call.respondBotUnavailable()
                call.respond(bot)
            }
            delete("/api/bots/{botId}") {
                if (call.rejectIfMaintenance()) return@delete
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfSuspended(userRepo, userId)) return@delete
                val botId = call.parameters["botId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing botId"))
                // 8.33 修复：删除 bot 会 bump memberRevision，但此前无广播，客户端成员列表残留
                val affectedGroupIds = com.maodouchat.server.repository.BotRepository.groupChatIdsFor(botId)
                val ok = com.maodouchat.server.repository.BotRepository.delete(botId, userId)
                if (!ok) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
                val groupSnapshots = conversationParticipantRepo.groupRevisionAndParticipantIds(affectedGroupIds)
                groupSnapshots.forEach { (chatId, snapshot) ->
                    notifyGroupRevisionChangedWithData(
                        json = json,
                        chatId = chatId,
                        reason = "BOT_REMOVED",
                        actorId = userId,
                        targetUserId = botId,
                        memberRevision = snapshot.first,
                        recipientIds = snapshot.second
                    )
                }
                call.respond(
                buildJsonObject {
put("ok", true)
                }
            )
            }
            put("/api/bots/{botId}/enabled") {
                if (call.rejectIfMaintenance()) return@put
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfSuspended(userRepo, userId)) return@put
                val botId = call.parameters["botId"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing botId"))
                val body = call.receiveBoundedTextOrEmpty()
                val enabled = runCatching {
                    val p = Json.parseToJsonElement(body).jsonObject["enabled"]?.jsonPrimitive
                    p?.booleanOrNull ?: p?.content?.toBooleanStrictOrNull()
                }.getOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("enabled required"))
                val bot = com.maodouchat.server.repository.BotRepository.setEnabled(botId, userId, enabled)
                    ?: return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
                call.respond(bot)
            }

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

// ─── 外部详情页 HTML 渲染 — 类似 t.me 的 /u/{username} ───────────

/** 8.51 修复 H1：完整 HTML 属性/文本转义（& < > " '），公开 HTML 模板必须全量使用。 */
private fun escapeHtml(value: String): String {
    val sb = StringBuilder(value.length + 16)
    for (c in value) {
        when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&#39;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

private val profilePageTokens = Regex("\\{\\{([A-Z0-9_]+)\\}\\}")

private val profilePageTemplate: String by lazy {
    Thread.currentThread().contextClassLoader?.getResource("public/profile.html")?.readText()
        ?: object {}.javaClass.classLoader.getResource("public/profile.html")?.readText()
        ?: ""
}

private fun renderProfileTemplate(values: Map<String, String>): String {
    val template = profilePageTemplate
    if (template.isBlank()) {
        val title = values["TITLE"].orEmpty()
        val description = values["DESCRIPTION"].orEmpty()
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"><title>$title</title></head><body><p>$description</p></body></html>"
    }
    return profilePageTokens.replace(template) { match ->
        values[match.groupValues[1]].orEmpty()
    }
}

private fun profileErrorDescription(error: String): String = when (error) {
    "用户不存在" -> "该用户不存在，或这张分享名片已经失效。"
    "用户名无效" -> "这个分享链接的用户名格式不正确。"
    "请求过于频繁，请稍后再试" -> "访问过于频繁，请稍后再打开这张名片。"
    else -> "暂时无法打开这张公开名片。"
}

private fun resolvePublicBaseUrl(baseUrl: String?): String {
    val raw = baseUrl?.trim().orEmpty()
    return when {
        raw == "/" || raw.startsWith("http://") || raw.startsWith("https://") -> raw.trimEnd('/')
        else -> ""
    }.ifBlank { "/" }.let { if (it == "/") "/" else it }
}

/**
 * 8.52 修复 AI-1：多模态 AI 输入 token 保守估算（防绕过日预算）。
 * 视觉/音频/文件按解码字节 256:1 折算（偏保守，接近真实成本量级），
 * 附加文本按 4 字符/token。此前 transcribe/analyze-image/analyze-file 用
 * estimateTokens("")≈1 token 预留预算，形同虚设。
 */
private fun estimateMultimodalTokens(byteCount: Long, extraText: String? = null): Long {
    val base = if (byteCount > 0) maxOf(1L, byteCount / 256) else 0L
    val textTokens = (extraText?.length ?: 0) / 4L
    return maxOf(1L, base + textTokens)
}
internal fun buildProfilePage(user: UserResponse?, baseUrl: String?, error: String?): String {
    // 8.51 修复 H1：公开主页存储型 XSS——所有用户字段完整 HTML 转义（含 " '）后再填模板
    val rawBase = resolvePublicBaseUrl(baseUrl)
    val escapedBase = escapeHtml(rawBase)
    val year = escapeHtml(java.time.Year.now().value.toString())
    val bodyClass = when {
        error != null -> "state-error"
        user != null -> "state-profile"
        else -> "state-empty"
    }
    val escapedError = error?.let(::escapeHtml).orEmpty()
    val errorDesc = error?.let { escapeHtml(profileErrorDescription(it)) }.orEmpty()
    val safeName = user?.name?.let(::escapeHtml).orEmpty()
    val safeStatus = user?.status?.let(::escapeHtml).orEmpty()
    val safeUsername = user?.username?.let(::escapeHtml).orEmpty()
    val rawAvatar = user?.avatar.orEmpty().trim()
    val isHttpAvatar = rawAvatar.startsWith("http://") || rawAvatar.startsWith("https://")
    val isSameOriginPath = rawAvatar.startsWith("/") && !rawAvatar.startsWith("//")
    val resolvedAvatar = when {
        isHttpAvatar -> rawAvatar
        isSameOriginPath && rawBase != "/" -> "$rawBase$rawAvatar"
        isSameOriginPath -> rawAvatar
        else -> ""
    }
    val safeAvatarUrl = if (resolvedAvatar.isNotBlank()) escapeHtml(resolvedAvatar) else ""
    val avatarImg = if (safeAvatarUrl.isNotBlank()) {
        """<img src="$safeAvatarUrl" alt="$safeName">"""
    } else ""
    val title = when {
        user != null -> "$safeName (@$safeUsername) — 毛豆聊天"
        error != null -> "$escapedError — 毛豆聊天"
        else -> "毛豆聊天"
    }
    val description = when {
        user != null -> {
            val statusText = safeStatus.takeIf { it.isNotBlank() } ?: "毛豆聊天用户"
            "$statusText · @$safeUsername 在毛豆聊天上的个人主页"
        }
        error != null -> errorDesc
        else -> "毛豆聊天 — 安全 · 轻量 · 智能的即时通讯"
    }
    val profileUrl = if (user != null && safeUsername.isNotBlank()) {
        if (rawBase == "/") "/u/$safeUsername" else "$escapedBase/u/$safeUsername"
    } else {
        escapedBase
    }
    val ogImageTags = if (safeAvatarUrl.isNotBlank()) {
        """<meta property="og:image" content="$safeAvatarUrl">"""
    } else ""
    val twitterCard = if (safeAvatarUrl.isNotBlank()) "summary_large_image" else "summary"
    val twitterImageTag = if (safeAvatarUrl.isNotBlank()) {
        """<meta name="twitter:image" content="$safeAvatarUrl">"""
    } else ""
    val classes = buildList {
        add(bodyClass)
        if (safeAvatarUrl.isBlank()) add("no-avatar")
        if (safeStatus.isNotBlank()) add("has-status")
    }.joinToString(" ")
    val initial = escapeHtml(user?.name?.firstOrNull()?.toString() ?: "?")
    val deepLink = if (safeUsername.isNotBlank()) "maodouchat://u/$safeUsername" else "maodouchat://"
    val intentLink = if (safeUsername.isNotBlank()) {
        "intent://u/$safeUsername#Intent;scheme=maodouchat;package=com.maodouchat;end"
    } else escapedBase

    return renderProfileTemplate(
        mapOf(
            "TITLE" to title,
            "DESCRIPTION" to description,
            "CANONICAL" to profileUrl,
            "OG_URL" to profileUrl,
            "OG_IMAGE_TAGS" to ogImageTags,
            "TWITTER_CARD" to twitterCard,
            "TWITTER_IMAGE_TAG" to twitterImageTag,
            "BODY_CLASS" to classes,
            "BASE_HREF" to escapedBase,
            "INITIAL" to initial,
            "AVATAR_URL" to safeAvatarUrl,
            "AVATAR_IMG" to avatarImg,
            "NAME" to safeName,
            "USERNAME" to safeUsername,
            "STATUS" to safeStatus,
            "DEEP_LINK" to deepLink,
            "INTENT_LINK" to intentLink,
            "ERROR_TITLE" to escapedError,
            "ERROR_DESC" to errorDesc,
            "YEAR" to year,
        )
    )
}

/** 9.131：bot 卡片/Hint 端点补齐实时 WS fanout（与 sendMessage/sendTable 等经典端点一致）。 */

/** 9.135：提示文案清洗——hint 进入 SYSTEM 消息并经 WS/FCM 分发到全群，
 * 控制字符/换行会污染客户端渲染与日志；压缩空白并截断到 120 字符。
 * 9.136：改为 internal 供 SecretSurfaceRouting 的 8 个 hint 端点复用。 */
internal fun sanitizeBotHint(raw: String?): String = (raw ?: "")
    .map { if (it.isISOControl() || it == '\u007F') ' ' else it }
    .joinToString("")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(120)

/** 9.136：改为 internal 供 SecretSurfaceRouting 的 8 个 hint 端点复用（9.131 仅覆盖 Routing.kt 内部家族）。 */
internal suspend fun fanoutBotMessage(
    userRepo: UserRepository,
    participantRepository: ConversationParticipantRepository,
    json: Json,
    botId: String,
    chatId: String,
    botMessage: MessageResponse,
    excludedRecipientIds: Set<String> = emptySet(),
) {
    val fanoutPids = participantRepository.participantIds(chatId)
    val botBlockedIds = try {
        userRepo.blockedEitherWayIdsInTx(botId, fanoutPids)
    } catch (_: Exception) {
        emptySet()
    }
    val result = com.maodouchat.server.messaging.v2.MessagingV2Repository().enqueueServiceMessage(
        message = botMessage,
        recipientUserIds = fanoutPids.filterNotTo(linkedSetOf()) {
            it in botBlockedIds || it in excludedRecipientIds
        },
    )
    val wakeup = json.encodeToString(WsMessage("INBOX_AVAILABLE_V2", "{}"))
    result.recipientUserIds.forEach { pid ->
        sendToUser(pid, wakeup)
    }
}

internal suspend fun fanoutBotEvent(
    userRepo: UserRepository,
    participantRepository: ConversationParticipantRepository,
    json: Json,
    botId: String,
    chatId: String,
    event: com.maodouchat.server.messaging.v2.ServiceMessagingV2Event,
    excludedRecipientIds: Set<String> = emptySet(),
) {
    val participantIds = participantRepository.participantIds(chatId)
    val blockedIds = try {
        userRepo.blockedEitherWayIdsInTx(botId, participantIds)
    } catch (_: Exception) {
        emptySet()
    }
    val now = System.currentTimeMillis()
    val result = com.maodouchat.server.messaging.v2.MessagingV2Repository().enqueueServiceEvent(
        id = "bot_event_" + UUID.randomUUID().toString().replace("-", ""),
        conversationId = chatId,
        senderUserId = botId,
        clientTimestamp = now,
        event = event,
        recipientUserIds = participantIds.filterNotTo(linkedSetOf()) {
            it in blockedIds || it in excludedRecipientIds
        },
    )
    val wakeup = json.encodeToString(WsMessage("INBOX_AVAILABLE_V2", "{}"))
    result.recipientUserIds.forEach { recipientId ->
        sendToUser(recipientId, wakeup)
    }
}

internal suspend fun fanoutSystemDelete(
    participantRepository: ConversationParticipantRepository,
    json: Json,
    chatId: String,
    messageId: String,
) {
    val participantIds = participantRepository.participantIds(chatId).toSet()
    val now = System.currentTimeMillis()
    val result = com.maodouchat.server.messaging.v2.MessagingV2Repository().enqueueServiceEvent(
        id = "system_event_" + UUID.randomUUID().toString().replace("-", ""),
        conversationId = chatId,
        senderUserId = "system",
        clientTimestamp = now,
        event = com.maodouchat.server.messaging.v2.ServiceMessagingV2Event(
            action = "DELETE",
            targetMessageId = messageId,
        ),
        recipientUserIds = participantIds,
    )
    val wakeup = json.encodeToString(WsMessage("INBOX_AVAILABLE_V2", "{}"))
    result.recipientUserIds.forEach { recipientId ->
        sendToUser(recipientId, wakeup)
    }
}
