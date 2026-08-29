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

private suspend fun ApplicationCall.rejectIfPostRestricted(userRepo: UserRepository, userId: String): Boolean {
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
    // 单账号连续登录失败锁定：5 次失败后锁定 15 分钟（防暴破；内存态，单实例有效）
    data class LoginLockout(var fails: Int, var lockUntil: Long, var lastFailureAt: Long = 0L)
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

        // ─── 认证 API ────────────────────────

        configurePublicUpdateRoutes(cacheService)
        configurePublicProfileRoutes(
            userRepository = userRepo,
            cacheService = cacheService,
            rateLimiter = publicProfileRateLimiter,
        )

 post("/api/auth/register") {
            if (ServerConfig.isProduction) {
                call.respond(HttpStatusCode.Gone, ErrorResponse("请使用验证码注册"))
                return@post
            }
            if (!RuntimeConfigService.isRegistrationAllowed()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("注册已关闭"))
                return@post
            }
            // 注册 IP 频率限制 — 防暴破（读取 AUTH_RATE_LIMIT_PER_MINUTE）
            if (!loginIpRateLimiter.acquire(call.remoteHost(), maxPerMinute = ServerConfig.authRateLimitPerMinute)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("注册过于频繁，请稍后再试"))
                return@post
            }
            val req = call.receiveBoundedText()?.let { parseJson<RegisterRequest>(it) }
            if (req == null || req.name.isBlank() || req.email.isBlank() || !isValidPassword(req.password)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@post
            }
            if (userRepo.getByEmail(req.email) != null) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("邮箱已注册"))
                return@post
            }
            // 0.74：一次性/垃圾邮箱域名黑名单（反垃圾注册）
            if (isRegistrationEmailDomainBlocked(req.email)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("该邮箱域名已被禁止注册", code = "EMAIL_DOMAIN_BLOCKED"))
                return@post
            }
            val user = try {
             // 用 try-catch 捕获并发注册时的唯一约束冲突（第二个请求在 byEmail 检查后才插入）
                userRepo.register(req.name, req.email, req.password)
            } catch (e: Exception) {
                // 8.34 修复：仅唯一约束冲突映射 409；其余异常（DB 故障等）此前被伪装成
                //「邮箱已注册」，掩盖真实失败、运维排障困难 → 交给 StatusPages 500 分支
                if (userRepo.isUniqueViolation(e)) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("邮箱已注册"))
                    return@post
                }
                throw e
            }
            if (user != null) {
                call.respond(issueAuthResponse(user, authTokenRepo))
            } else {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("注册失败"))
            }
        }

        post("/api/auth/login") {
            // 登录 IP 频率限制 — 防暴破（读取 AUTH_RATE_LIMIT_PER_MINUTE）
            if (!loginIpRateLimiter.acquire(call.remoteHost(), maxPerMinute = ServerConfig.authRateLimitPerMinute)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("登录过于频繁，请稍后再试"))
                return@post
            }
            val req = call.receiveBoundedText()?.let { parseJson<LoginRequest>(it) }
            if (req == null || req.email.isBlank() || req.password.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("邮箱或密码不能为空"))
                return@post
            }
            // 按账号限流：即使攻击者轮换源 IP，对同一邮箱的尝试仍受限于单机速率
            val emailKey = runCatching { req.email.normalizedEmail() }.getOrDefault(req.email)
            if (!loginEmailRateLimiter.acquire(emailKey, maxPerMinute = ServerConfig.authRateLimitPerMinute)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("该账号尝试过于频繁，请稍后再试"))
                return@post
            }
            // 单账号失败锁定检查：锁定期间直接拒绝，不泄露密码正误（与失败提示一致）
            // 8.51 修复 M1：锁定按「账号|源 IP」隔离，远程失败不影响受害者自身 IP 登录
            val ip = call.remoteHost()
            sweepLoginLockouts()
            val accountLockKey = "$emailKey|$ip"
            val lock = loginLockouts[accountLockKey]
            if (lock != null && lock.lockUntil > System.currentTimeMillis()) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("该账号已被临时锁定，请稍后再试", code = "ACCOUNT_LOCKED"))
                return@post
            }
            // 8.40：锁定期满即清除失败计数——此前计数只随成功登录清空，期满后任意一次
            // 失败会立即重新锁定 15 分钟，攻击者只需周期性错 1 次即可无限期锁死账号（可用性 DoS）。
            // 仅清除「确实锁定过且已过期」的条目：lockUntil=0 表示从未锁定，不得移除，
            // 否则每次失败后计数被清空、锁定永远不会触发。
            if (lock != null && lock.lockUntil > 0L && lock.lockUntil <= System.currentTimeMillis()) {
                loginLockouts.remove(accountLockKey)
            }
            val loginResult = userRepo.loginWithFactors(req.email, req.password, req.totpCode)
            val authed = loginResult.user != null
            // 9.5xx：登录全链路日志——管理后台「登不进」排障：每次尝试记录账号/来源/结果
            loginAuditLogger.info(
                "login attempt email={} ip={} user={} passwordOk={} totpEnabled={} totpOk={}",
                emailKey,
                ip,
                loginResult.user?.id.orEmpty(),
                loginResult.passwordOk,
                loginResult.totpEnabled,
                loginResult.totpOk
            )
            if (!authed) {
                // 密码错误 / TOTP 失败均计入连续失败，达阈值即锁定（按 IP 隔离）
                recordLoginFailure(emailKey, ip)
            }
            when {
                !loginResult.passwordOk -> {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid credentials", code = "AUTH_INVALID"))
                }
                loginResult.totpEnabled && !loginResult.totpOk -> {
                    // 200 so clients can parse requiresTotp without treating it as transport failure.
                    call.respond(
                        AuthResponse(requiresTotp = true, totpEnabled = true, userId = "", name = "", token = "")
                    )
                }
                authed -> {
                    // 登录成功：清除失败计数（按 IP 隔离），避免历史失败触发误锁
                    loginLockouts.remove(accountLockKey)
                    authTokenRepo.deleteExpired()
                    call.respond(issueAuthResponse(checkNotNull(loginResult.user), authTokenRepo).copy(totpEnabled = loginResult.totpEnabled))
                }
                else -> {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid credentials", code = "AUTH_INVALID"))
                }
            }
        
        }

        // 发送验证码 — 在 Dispatchers.IO 中同步阻塞等待邮件发送完成，避免阻塞 Netty 事件线程
            // purpose=register（默认）| reset；重置密码不依赖 allowRegistration
            post("/api/auth/send-code") {
                val req = call.receiveBoundedText()?.let { parseJson<SendCodeRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@post
                }
                val purpose = req.purpose.trim().lowercase().ifBlank { "register" }
                val isReset = purpose == com.maodouchat.server.service.EmailService.PURPOSE_RESET
                if (!isReset && !RuntimeConfigService.isRegistrationAllowed()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("注册已关闭"))
                    return@post
                }
                val email = runCatching { req.email.normalizedEmail() }.getOrNull()
                if (email == null || email.isBlank() || !email.contains("@")) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("邮件格式无效"))
                    return@post
                }
                // Reject blocked registration domains before consuming limiter capacity or
                // sending mail. Password-reset requests remain intentionally unaffected.
                if (!isReset && isRegistrationEmailDomainBlocked(email)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("该邮箱域名已被禁止注册", code = "EMAIL_DOMAIN_BLOCKED")
                    )
                    return@post
                }
                // 频率限制：先检查 IP 级限制（防邮件轰炸），再检查邮箱级限制
                // 顺序很重要：如果先消费邮箱配额再被 IP 拒绝，共享 IP 下的用户会被误伤
                if (!sendCodeIpRateLimiter.acquireSendCodeIp(call.remoteHost())) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("该网络发送验证码次数过多，请稍后再试"))
                    return@post
                }
                // 每邮箱每分钟最多 3 次；拒绝请求不再继续扩充时间戳列表。
                if (!sendCodeRateLimiter.acquire(email, maxPerMinute = 3)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("发送过于频繁，请稍后再试"))
                    return@post
                }
                // 重置密码：账号不存在时仍返回 ok，避免邮箱枚举；内部跳过发信
                if (isReset && userRepo.getByEmail(email) == null) {
                    // 8.47 修复：等价延迟抗时间侧信道——已注册邮箱走 SMTP（数百 ms~数秒），
                    // 不存在邮箱即时返回可被攻击者测量时差枚举注册邮箱。统一延迟到可比量级。
                    withContext(Dispatchers.IO) { kotlinx.coroutines.delay(400L) }
                    call.respond(
                buildJsonObject {
put("status", "ok")
put("message", "验证码已发送")
                }
            )
                    return@post
                }
                try {
                    withContext(Dispatchers.IO) {
                        com.maodouchat.server.service.EmailService.sendVerificationCode(
                            email,
                            purpose = if (isReset) com.maodouchat.server.service.EmailService.PURPOSE_RESET
                            else com.maodouchat.server.service.EmailService.PURPOSE_REGISTER
                        )
                    }
                } catch (_: IllegalStateException) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("验证码邮件发送失败，请稍后重试"))
                    return@post
                }
                call.respond(
                buildJsonObject {
put("status", "ok")
put("message", "验证码已发送")
                }
            )
            }

        // 带验证码注册
post("/api/auth/register-with-code") {
if (!RuntimeConfigService.isRegistrationAllowed()) {
call.respond(HttpStatusCode.Forbidden, ErrorResponse("注册已关闭"))
return@post
}
// 注册 IP 频率限制 — 防暴破（读取 AUTH_RATE_LIMIT_PER_MINUTE）
                if (!loginIpRateLimiter.acquire(call.remoteHost(), maxPerMinute = ServerConfig.authRateLimitPerMinute)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("注册过于频繁，请稍后再试"))
                    return@post
                }
                val req = call.receiveBoundedText()?.let { parseJson<RegisterWithCodeRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@post
                }
            if (req.name.isBlank() || req.email.isBlank() || !isValidPassword(req.password)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@post
            }
            // 按账号限流：关闭「多源 IP 分布式爆破/刷注册」绕过
            val regEmailKey = runCatching { req.email.normalizedEmail() }.getOrDefault(req.email)
            if (!loginEmailRateLimiter.acquire(regEmailKey, maxPerMinute = ServerConfig.authRateLimitPerMinute)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("该账号操作过于频繁，请稍后再试"))
                return@post
            }
            if (!com.maodouchat.server.service.EmailService.verifyCode(req.email, req.code)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("验证码无效或已过期"))
                return@post
            }
            if (userRepo.getByEmail(req.email) != null) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("邮箱已注册"))
                return@post
            }
            if (isRegistrationEmailDomainBlocked(req.email)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("该邮箱域名已被禁止注册", code = "EMAIL_DOMAIN_BLOCKED"))
                return@post
            }
            // 用 try-catch 捕获并发注册时的唯一约束冲突，与 /api/auth/register 保持一致
            val user = try {
                userRepo.register(req.name, req.email, req.password)
            } catch (e: Exception) {
                // 8.34 修复：仅唯一约束冲突映射 409，其余异常如实抛出（500），不再伪装成「邮箱已注册」
                if (userRepo.isUniqueViolation(e)) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("邮箱已注册"))
                    return@post
                }
                throw e
            }
            if (user != null) {
                call.respond(issueAuthResponse(user, authTokenRepo))
            } else {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("注册失败"))
            }
        }

        // 忘记密码：验证码 + 新密码；成功后吊销全部会话
        post("/api/auth/reset-password") {
            if (!loginIpRateLimiter.acquire(call.remoteHost(), maxPerMinute = ServerConfig.authRateLimitPerMinute)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                return@post
            }
            val req = call.receiveBoundedText()?.let { parseJson<ResetPasswordRequest>(it) } ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@post
            }
            val email = req.email.normalizedEmail()
            // 8.40：先校验再按账号限流（此前空邮箱先占限流额度再被 429 拒绝，且空值也扣配额）
            if (email.isBlank() || !email.contains("@") || req.code.isBlank() || !isValidPassword(req.newPassword)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效，新密码至少 6 位"))
                return@post
            }
            // 按账号限流：关闭「多源 IP 分布式爆破重置同一账号」绕过
            if (!loginEmailRateLimiter.acquire(email, maxPerMinute = ServerConfig.authRateLimitPerMinute)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("该账号操作过于频繁，请稍后再试"))
                return@post
            }
            if (!com.maodouchat.server.service.EmailService.verifyCode(
                    email,
                    req.code,
                    purpose = com.maodouchat.server.service.EmailService.PURPOSE_RESET
                )
            ) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("验证码无效或已过期"))
                return@post
            }
            val userId = userRepo.resetPasswordByEmail(email, req.newPassword)
            if (userId == null) {
                // 验证码已消费；账号异常时与「码错误」区分开但避免枚举细节
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("无法重置密码，请确认邮箱后重试"))
                return@post
            }
            authTokenRepo.rotateAccessTokenVersion(userId)
            pushTokenRepo.removeAllForUser(userId)
            disconnectUserSessions(userId, "密码已重置，请重新登录")
            call.respond(
                buildJsonObject {
put("status", "ok")
put("message", "密码已重置，请使用新密码登录")
                }
            )
        }

        post("/api/auth/refresh") {
            // 9.3xx：此前共享 10/分/IP 的登录限流器——多设备/401 重试并发下正常轮换都被 429，
            // 客户端 refresh 失败即"假登录"（UI 卡在旧数据、无法恢复会话）。
            // refresh 本身有一次性轮换吊销兜底，这里放宽到 120/分/IP。
            if (!loginIpRateLimiter.acquire(call.remoteHost(), maxPerMinute = maxOf(ServerConfig.authRateLimitPerMinute, 120))) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                return@post
            }
            val req = call.receiveBoundedText()?.let { parseJson<RefreshTokenRequest>(it) } ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@post
            }
            // 单事务：校验封禁/账号存在后再 revoke，避免 peek→consume 窗口烧 refresh
            when (val rotated = authTokenRepo.rotateIfEligible(req.refreshToken.trim())) {
                is AuthTokenRepository.RotateRefreshResult.InvalidToken -> {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("登录已过期，请重新登录"))
                    return@post
                }
                is AuthTokenRepository.RotateRefreshResult.UserSuspended -> {
                    // 8.42：与 InvalidToken 统一 401 通用文案——否则持有他人 refresh token 的
                    // 攻击者可探测账号封禁状态（账号状态 oracle）；封禁账号客户端走 401 正常登出
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("登录已过期，请重新登录"))
                    return@post
                }
                is AuthTokenRepository.RotateRefreshResult.UserMissing -> {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("登录已过期，请重新登录"))
                    return@post
                }
                is AuthTokenRepository.RotateRefreshResult.SessionCompromised -> {
                    disconnectUserSessionsByAuthSessionIds(
                        rotated.userId,
                        setOf(rotated.sessionId),
                        "登录会话存在令牌重用，已撤销"
                    )
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("登录已过期，请重新登录"))
                    return@post
                }
                is AuthTokenRepository.RotateRefreshResult.Success -> {
                    val user = userRepo.getById(rotated.userId)
                    if (user == null) {
                        // 消费后账号被删的极端竞态：refresh 已吊销，只能要求重新登录
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("用户不存在"))
                        return@post
                    }
                    val response = issueAuthResponse(
                        user,
                        authTokenRepo,
                        IssuedRefreshToken(
                            token = rotated.refreshToken,
                            expiresAt = rotated.refreshExpiresAt,
                            sessionId = rotated.sessionId
                        )
                    )
                    call.respond(response)
                }
            }
        }

        post("/api/auth/logout") {
            // 9.3xx：登出必须永远可用——共享 10/分/IP 登录限流器导致"假登录"（会话 401 风暴后
            // logout 429，purge 流程中断，UI 永久卡在旧数据）
            if (!loginIpRateLimiter.acquire(call.remoteHost(), maxPerMinute = maxOf(ServerConfig.authRateLimitPerMinute, 120))) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                return@post
            }
            val req = call.receiveBoundedText()?.let { parseJson<RefreshTokenRequest>(it) } ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@post
            }
            // 优先从 refresh 行解析 userId（body-only logout 也能踢 WS）
            val revokedRefreshSession = authTokenRepo.revokeAndGetSession(req.refreshToken.trim())
            authTokenRepo.revokeAccessTokenFromAuthorizationHeader(call.request.headers[HttpHeaders.Authorization])
            val accessToken = call.request.headers[HttpHeaders.Authorization]
                ?.trim()
                ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substringAfter(' ')
                ?.trim()
                .orEmpty()
            val accessJwt = accessToken.takeIf { it.isNotBlank() }?.let {
                com.maodouchat.server.auth.JwtConfig.verifyToken(it)
            }
            val sessionsByUser = mutableMapOf<String, MutableSet<String>>()
            revokedRefreshSession?.sessionId?.let { sessionId ->
                sessionsByUser.getOrPut(revokedRefreshSession.userId) { mutableSetOf() }.add(sessionId)
            }
            val accessUserId = accessJwt?.subject?.takeIf { it.isNotBlank() }
            val accessSessionId = accessJwt?.let(JwtConfig::authSessionId)
            if (accessUserId != null && accessSessionId != null) {
                sessionsByUser.getOrPut(accessUserId) { mutableSetOf() }.add(accessSessionId)
            }
            sessionsByUser.forEach { (userId, sessionIds) ->
                sessionIds.forEach { sessionId ->
                    authTokenRepo.revokeSession(userId, sessionId)
                    pushTokenRepo.removeForAuthSession(userId, sessionId)
                }
                disconnectUserSessionsByAuthSessionIds(userId, sessionIds, "已退出登录")
            }
            val logoutUserId = revokedRefreshSession?.userId ?: accessUserId
            if (!logoutUserId.isNullOrBlank()) {
                if (sessionsByUser[logoutUserId].isNullOrEmpty()) {
                    disconnectUserSessionsByAccessJti(logoutUserId, accessJwt?.id, "已退出登录")
                }
                // 可选 deviceId：清除本机 FCM，避免已退出设备仍收推送（Android 也会先 unregister，此处兜底）
                val pushDeviceId = req.deviceId.trim()
                if (pushDeviceId.isNotBlank() && pushDeviceId.length <= 128) {
                    pushTokenRepo.remove(logoutUserId, pushDeviceId)
                }
            }
            call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
        }

        // ─── 官网静态页面（无需认证） ─────────────

        get("/") {
            // 9.206：第三方部署可关闭官网（PUBLIC_SITE=false）——首页改为极简服务器名片
            if (!com.maodouchat.server.config.ServerConfig.publicSiteEnabled) {
                fun esc(value: String): String = value
                    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&#39;")
                val name = esc(System.getenv("SERVER_NAME")?.takeIf { it.isNotBlank() } ?: "Maodouchat Server")
                val desc = esc(System.getenv("SERVER_DESCRIPTION").orEmpty())
                call.respondText(
                    // 9.289：极简名片页风格对齐 /u/ 公开主页（浅色白卡+品牌蓝，去深色裸页感）
                    """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/><meta name="robots" content="noindex"/><title>$name</title><style>*{margin:0;padding:0;box-sizing:border-box}body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;background:#f4f5f7;min-height:100vh;display:flex;align-items:center;justify-content:center;color:#23272b}.card{background:#fff;border-radius:16px;box-shadow:0 1px 3px rgba(16,24,40,.06);padding:40px 32px;max-width:420px;width:calc(100% - 32px);text-align:center}.mark{width:56px;height:56px;border-radius:14px;background:#3390EC;color:#fff;font-size:24px;font-weight:600;display:flex;align-items:center;justify-content:center;margin:0 auto 16px}h1{font-size:20px;font-weight:600;color:#111418;margin-bottom:6px}.desc{font-size:14px;color:#6b7280;line-height:1.6;margin-bottom:14px}.foot{font-size:12px;color:#a2a8b0}@media (prefers-color-scheme:dark){body{background:#101418}.card{background:#1a1f24;box-shadow:none}h1{color:#f2f4f6}.desc{color:#9aa1a9}}</style></head><body><div class="card"><div class="mark">毛</div><h1>$name</h1>${if (desc.isNotBlank()) "<p class=\"desc\">$desc</p>" else ""}<p class="foot">Powered by Maodouchat Server</p></div></body></html>""",
                    ContentType.Text.Html
                )
                return@get
            }
            call.respondPublicHtml("index")
        }
        get("/assets/site.css") {
            val css = this::class.java.classLoader.getResource("public/assets/site.css")?.readText()
                ?: "body{font-family:sans-serif}"
            call.respondText(css, io.ktor.http.ContentType.Text.CSS)
        }
        get("/assets/home.css") {
            val css = this::class.java.classLoader.getResource("public/assets/home.css")?.readText()
                ?: ""
            call.respondText(css, io.ktor.http.ContentType.Text.CSS)
        }
        get("/assets/profile.css") {
            val css = this::class.java.classLoader.getResource("public/assets/profile.css")?.readText()
                ?: ""
            call.respondText(css, io.ktor.http.ContentType.Text.CSS)
        }
        get("/assets/style.css") {
            val css = this::class.java.classLoader.getResource("public/assets/style.css")?.readText()
                ?: ""
            call.respondText(css, io.ktor.http.ContentType.Text.CSS)
        }
        get("/assets/developer.css") {
            val css = this::class.java.classLoader.getResource("public/assets/developer.css")?.readText()
                ?: ""
            call.respondText(css, io.ktor.http.ContentType.Text.CSS)
        }
        get("/assets/developer.js") {
            val js = this::class.java.classLoader.getResource("public/assets/developer.js")?.readText()
                ?: ""
            call.respondText(js, io.ktor.http.ContentType.Application.JavaScript)
        }
        get("/developer") {
            call.respondPublicHtml("developer", "<h1>Developer Console</h1>")
        }
        get("/developer.html") {
            call.respondRedirect("/developer", permanent = true)
        }
        // ─── 官网静态页面（开发者 / 隐私 / 条款 / 安全） ───
        get("/privacy") {
            call.respondPublicHtml("privacy", "<h1>Privacy Policy</h1>")
        }
        get("/privacy.html") {
            call.respondRedirect("/privacy", permanent = true)
        }
        get("/terms") {
            call.respondPublicHtml("terms", "<h1>Terms of Service</h1>")
        }
        get("/terms.html") {
            call.respondRedirect("/terms", permanent = true)
        }
        get("/security") {
            call.respondPublicHtml("security", "<h1>Security</h1>")
        }
        get("/security.html") {
            call.respondRedirect("/security", permanent = true)
        }
        // 旧页面永久重定向到首页相应模块
        get("/faq") {
            call.respondRedirect("/#faq", permanent = true)
        }
        get("/faq.html") {
            call.respondRedirect("/#faq", permanent = true)
        }
        get("/help") {
            call.respondRedirect("/#faq", permanent = true)
        }
        get("/help.html") {
            call.respondRedirect("/#faq", permanent = true)
        }
        get("/assets/logo.png") {
            val bytes = this::class.java.classLoader.getResourceAsStream("public/assets/logo.png")?.use { it.readBytes() }
            if (bytes != null) {
                call.respondBytes(bytes, io.ktor.http.ContentType.Image.PNG)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
        get("/assets/icon-192.png") {
            val bytes = this::class.java.classLoader.getResourceAsStream("public/assets/icon-192.png")?.use { it.readBytes() }
            if (bytes != null) {
                call.respondBytes(bytes, io.ktor.http.ContentType.Image.PNG)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
        get("/assets/icon-512.png") {
            val bytes = this::class.java.classLoader.getResourceAsStream("public/assets/icon-512.png")?.use { it.readBytes() }
            if (bytes != null) {
                call.respondBytes(bytes, io.ktor.http.ContentType.Image.PNG)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
        get("/sitemap.xml") {
            val base = ServerConfig.baseUrl.trimEnd('/')
            val pages = listOf("", "developer", "security", "privacy", "terms")
            val urls = pages.joinToString("") { page ->
                val loc = if (page.isBlank()) "$base/" else "$base/$page"
                "<url><loc>$loc</loc><changefreq>weekly</changefreq></url>"
            }
            call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
            call.respondText(
                """<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">$urls</urlset>""",
                io.ktor.http.ContentType.Text.Xml
            )
        }
        get("/.well-known/security.txt") {
            val base = ServerConfig.baseUrl.trimEnd('/')
            call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
            call.respondText(
                "Contact: mailto:security@maodouchat.com\nPreferred-Languages: zh, en\nCanonical: $base/.well-known/security.txt\nPolicy: $base/security#disclosure\nExpires: 2027-08-13T00:00:00.000Z\n",
                io.ktor.http.ContentType.Text.Plain
            )
        }
        get("/security.txt") {
            call.respondRedirect("/.well-known/security.txt", permanent = true)
        }
        get("/manifest.webmanifest") {
            val manifest = Thread.currentThread().contextClassLoader
                ?.getResource("public/manifest.webmanifest")?.readText()
                ?: object {}.javaClass.classLoader.getResource("public/manifest.webmanifest")?.readText()
                ?: "{}"
            call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
            call.respondText(manifest, io.ktor.http.ContentType.Application.Json)
        }
        get("/sw.js") {
            val sw = Thread.currentThread().contextClassLoader
                ?.getResource("public/sw.js")?.readText()
                ?: object {}.javaClass.classLoader.getResource("public/sw.js")?.readText()
                ?: ""
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondText(sw, io.ktor.http.ContentType.Application.JavaScript)
        }
        get("/robots.txt") {
            val base = ServerConfig.baseUrl.trimEnd('/')
            call.respondText(
                "User-agent: *\nAllow: /\nDisallow: /admin\nDisallow: /developer\nDisallow: /developer.html\nDisallow: /api/\nSitemap: $base/sitemap.xml\n",
                io.ktor.http.ContentType.Text.Plain
            )
        }
authenticate("auth-jwt") {
            post("/api/attachment-uploads") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfMessageRestricted(userRepo, userId)) return@post
                val request = call.receiveBoundedText()?.let { parseJson<AttachmentUploadSessionRequest>(it) }
                if (
                    request == null ||
                    request.chatId.isBlank() ||
                    !CLIENT_MESSAGE_ID_REGEX.matches(request.messageId) ||
                    !request.cipherSha256.lowercase().matches(Regex("^[a-f0-9]{64}$")) ||
                    request.cipherSize !in 17L..MAX_ATTACHMENT_CIPHER_BYTES
                ) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件上传会话参数无效"))
                    return@post
                }
                if (!conversationParticipantRepo.isParticipant(request.chatId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权向该聊天上传附件"))
                    return@post
                }
                if (conversationQueryRepo.getById(request.chatId)?.isGroup == true && conversationParticipantRepo.isMuted(request.chatId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("你已被禁言，暂时无法上传附件"))
                    return@post
                }
                if (!encryptedAttachmentRepo.hasCapacityFor(userId, request.chatId, request.messageId, request.cipherSize, maxAttachmentUserBytes)) {
                    call.respond(ATTACHMENT_QUOTA_STATUS, ErrorResponse("附件存储配额不足"))
                    return@post
                }
                if (!aiRateLimiter.acquire("attachment_session:$userId", maxPerMinute = 40)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("附件上传过于频繁"))
                    return@post
                }
                val attachmentId = "att_${UUID.randomUUID().toString().replace("-", "")}" 
                val expiresAt = System.currentTimeMillis() + ATTACHMENT_UPLOAD_TTL_MS
                val created = runCatching {
                    encryptedAttachmentRepo.createUploadSession(
                        id = attachmentId,
                        chatId = request.chatId,
                        uploaderId = userId,
                        pendingMessageId = request.messageId,
                        sha256 = request.cipherSha256.lowercase(),
                        cipherSize = request.cipherSize,
                        expiresAt = expiresAt,
                        maxUserBytes = maxAttachmentUserBytes
                    )
                }
                val session = created.getOrElse { error ->
                    when (error) {
                        is AttachmentQuotaExceededException -> call.respond(ATTACHMENT_QUOTA_STATUS, ErrorResponse("附件存储配额不足"))
                        is AttachmentMessageAlreadyUsedException -> call.respond(HttpStatusCode.Conflict, ErrorResponse("消息 ID 已被使用"))
                        is AttachmentNotAllowedException -> {
                            val msg = when (error.message) {
                                "muted" -> "你已被禁言，暂时无法上传附件"
                                "not_participant", "chat_not_found" -> "无权向该聊天上传附件"
                                else -> "无权向该聊天上传附件"
                            }
                            call.respond(HttpStatusCode.Forbidden, ErrorResponse(msg))
                        }
                        else -> {
                            call.application.log.warn("Encrypted attachment session creation failed", error)
                            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("附件上传会话创建失败"))
                        }
                    }
                    return@post
                }
                session.replacedIds.forEach(EncryptedAttachmentStorage::delete)
                val refreshed = reconcileAttachmentUpload(session.record, encryptedAttachmentRepo, userId)
                if (refreshed == null) {
                    encryptedAttachmentRepo.removeUncommitted(session.record.id, userId)
                    EncryptedAttachmentStorage.delete(session.record.id)
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件总哈希校验失败"))
                    return@post
                }
                call.respond(
                    if (session.reused) HttpStatusCode.OK else HttpStatusCode.Created,
                    refreshed.toUploadStatus()
                )
            }

            get("/api/attachment-uploads/{id}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val attachmentId = call.parameters["id"].orEmpty()
                val record = encryptedAttachmentRepo.get(attachmentId)
                if (record == null || record.uploaderId != userId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("附件上传会话不存在"))
                    return@get
                }
                if (!conversationParticipantRepo.isParticipant(record.chatId, userId)) {
                    encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                    // 9.151：已 COMMITTED 的附件密文仍被群内其他成员下载，
                    // 上传者退群后重查状态/重传不得连带删除 .bin
                    if (record.status != "COMMITTED") EncryptedAttachmentStorage.delete(attachmentId)
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("已不在该聊天中"))
                    return@get
                }
                if (record.status == "COMMITTED") {
                    call.respond(record.toUploadStatus())
                    return@get
                }
                if (record.expiresAt != null && record.expiresAt <= System.currentTimeMillis()) {
                    encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                    EncryptedAttachmentStorage.delete(attachmentId)
                    call.respond(HttpStatusCode.Gone, ErrorResponse("附件上传会话已过期"))
                    return@get
                }
                val reconciled = reconcileAttachmentUpload(record, encryptedAttachmentRepo, userId)
                if (reconciled == null) {
                    EncryptedAttachmentStorage.delete(attachmentId)
                    encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件总哈希校验失败"))
                    return@get
                }
                call.respond(reconciled.toUploadStatus())
            }

            put("/api/attachment-uploads/{id}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfMessageRestricted(userRepo, userId)) return@put
                val attachmentId = call.parameters["id"].orEmpty()
                val offset = call.request.queryParameters["offset"]?.toLongOrNull()
                val chunkHash = call.request.header(ATTACHMENT_CHUNK_HASH_HEADER)?.lowercase().orEmpty()
                val declaredLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
                val record = encryptedAttachmentRepo.get(attachmentId)
                if (record == null || record.uploaderId != userId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("附件上传会话不存在"))
                    return@put
                }
                if (!conversationParticipantRepo.isParticipant(record.chatId, userId)) {
                    encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                    // 9.151：同 GET——COMMITTED 附件密文不可因上传者退群后的重传被删除
                    if (record.status != "COMMITTED") EncryptedAttachmentStorage.delete(attachmentId)
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("已不在该聊天中"))
                    return@put
                }
                if (conversationQueryRepo.getById(record.chatId)?.isGroup == true && conversationParticipantRepo.isMuted(record.chatId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("你已被禁言，暂时无法上传附件"))
                    return@put
                }
                if (record.expiresAt != null && record.expiresAt <= System.currentTimeMillis()) {
                    encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                    EncryptedAttachmentStorage.delete(attachmentId)
                    call.respond(HttpStatusCode.Gone, ErrorResponse("附件上传会话已过期"))
                    return@put
                }
                if (!aiRateLimiter.acquire("attachment_chunk:$userId", maxPerMinute = 180)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("附件分块上传过于频繁"))
                    return@put
                }
                if (record.status != "UPLOADING") {
                    call.respond(record.toUploadStatus())
                    return@put
                }
                if (
                    offset == null ||
                    declaredLength == null || declaredLength !in 1L..MAX_ATTACHMENT_CHUNK_BYTES ||
                    offset < 0L || offset + declaredLength > record.cipherSize ||
                    !chunkHash.matches(Regex("^[a-f0-9]{64}$")) ||
                    call.request.contentType().withoutParameters() != ContentType.Application.OctetStream
                ) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件分块参数无效"))
                    return@put
                }
                val chunk = call.receiveEncryptedAttachmentChunk(MAX_ATTACHMENT_CHUNK_BYTES.toInt())
                if (chunk == null || chunk.size.toLong() != declaredLength || chunk.sha256Hex() != chunkHash) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件分块长度或哈希无效"))
                    return@put
                }
                when (val appended = withContext(Dispatchers.IO) {
                    EncryptedAttachmentStorage.appendChunk(attachmentId, offset, chunk, record.cipherSize)
                }) {
                    is EncryptedAttachmentStorage.AppendResult.OffsetMismatch -> {
                        call.respond(HttpStatusCode.Conflict, record.toUploadStatus(appended.uploadedBytes))
                    }
                    EncryptedAttachmentStorage.AppendResult.ContentMismatch -> {
                        call.respond(HttpStatusCode.Conflict, ErrorResponse("附件分块与已上传内容冲突"))
                    }
                    is EncryptedAttachmentStorage.AppendResult.Accepted -> {
                        if (!encryptedAttachmentRepo.updateUploadProgress(attachmentId, userId, appended.uploadedBytes)) {
                            encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                            EncryptedAttachmentStorage.delete(attachmentId)
                            call.respond(HttpStatusCode.Conflict, ErrorResponse("附件上传会话已被替换"))
                            return@put
                        }
                        if (appended.uploadedBytes == record.cipherSize) {
                            if (withContext(Dispatchers.IO) { EncryptedAttachmentStorage.sha256(attachmentId) } != record.cipherSha256) {
                                encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                                EncryptedAttachmentStorage.delete(attachmentId)
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件总哈希校验失败"))
                                return@put
                            }
                            val finalized = withContext(Dispatchers.IO) {
                                EncryptedAttachmentStorage.finalizeResumableUpload(attachmentId)
                            }
                            if (finalized == null || !encryptedAttachmentRepo.markUploaded(attachmentId, userId)) {
                                encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)
                                EncryptedAttachmentStorage.delete(attachmentId)
                                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("附件完成状态保存失败"))
                                return@put
                            }
                        }
                        val updated = encryptedAttachmentRepo.get(attachmentId) ?: record.copy(uploadedBytes = appended.uploadedBytes)
                        call.respond(updated.toUploadStatus(appended.uploadedBytes))
                    }
                }
            }

            post("/api/attachments") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfMessageRestricted(userRepo, userId)) return@post
                val chatId = call.request.queryParameters["chatId"].orEmpty()
                val pendingMessageId = call.request.queryParameters["messageId"].orEmpty()
                val expectedHash = call.request.header(ATTACHMENT_HASH_HEADER)?.lowercase().orEmpty()
                val declaredLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
                if (chatId.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("聊天 ID 无效"))
                    return@post
                }
                if (!conversationParticipantRepo.isParticipant(chatId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权向该聊天上传附件"))
                    return@post
                }
                if (conversationQueryRepo.getById(chatId)?.isGroup == true && conversationParticipantRepo.isMuted(chatId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("你已被禁言，暂时无法上传附件"))
                    return@post
                }
                if (!CLIENT_MESSAGE_ID_REGEX.matches(pendingMessageId)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件消息 ID 无效"))
                    return@post
                }
                if (call.request.contentType().withoutParameters() != ContentType.Application.OctetStream) {
                    call.respond(HttpStatusCode.UnsupportedMediaType, ErrorResponse("附件必须使用二进制上传"))
                    return@post
                }
                if (!expectedHash.matches(Regex("^[a-f0-9]{64}$"))) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件哈希无效"))
                    return@post
                }
                if (declaredLength == null || declaredLength !in 17L..MAX_ATTACHMENT_CIPHER_BYTES) {
                    call.respond(ATTACHMENT_TOO_LARGE_STATUS, ErrorResponse("附件大小无效或超过限制"))
                    return@post
                }
                if (!encryptedAttachmentRepo.hasCapacityFor(userId, chatId, pendingMessageId, declaredLength, maxAttachmentUserBytes)) {
                    call.respond(ATTACHMENT_QUOTA_STATUS, ErrorResponse("附件存储配额不足"))
                    return@post
                }
                if (!aiRateLimiter.acquire("attachment_upload:$userId", maxPerMinute = 20)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("附件上传过于频繁"))
                    return@post
                }
                val attachmentId = "att_${UUID.randomUUID().toString().replace("-", "")}" 
                val tempFile = EncryptedAttachmentStorage.createTempFile(attachmentId)
                val received = try {
                    call.receiveEncryptedAttachment(tempFile, MAX_ATTACHMENT_CIPHER_BYTES)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    EncryptedAttachmentStorage.delete(attachmentId)
                    throw cancelled
                } catch (error: Throwable) {
                    EncryptedAttachmentStorage.delete(attachmentId)
                    call.application.log.warn("Encrypted attachment receive failed", error)
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件上传中断"))
                    return@post
                }
                if (received == null || received.byteCount != declaredLength || received.sha256 != expectedHash) {
                    EncryptedAttachmentStorage.delete(attachmentId)
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("附件长度或哈希校验失败"))
                    return@post
                }
                val expiresAt = System.currentTimeMillis() + ATTACHMENT_UPLOAD_TTL_MS
                val stored = runCatching {
                    EncryptedAttachmentStorage.finalizeUpload(attachmentId, tempFile)
                    encryptedAttachmentRepo.createReplacingPending(
                        id = attachmentId,
                        chatId = chatId,
                        uploaderId = userId,
                        pendingMessageId = pendingMessageId,
                        sha256 = received.sha256,
                        cipherSize = received.byteCount,
                        expiresAt = expiresAt,
                        maxUserBytes = maxAttachmentUserBytes
                    )
                }
                if (stored.isFailure) {
                    EncryptedAttachmentStorage.delete(attachmentId)
                    when (val error = stored.exceptionOrNull()) {
                        is AttachmentQuotaExceededException -> call.respond(ATTACHMENT_QUOTA_STATUS, ErrorResponse("附件存储配额不足"))
                        is AttachmentMessageAlreadyUsedException -> call.respond(HttpStatusCode.Conflict, ErrorResponse("消息 ID 已被使用"))
                        is AttachmentNotAllowedException -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权向该聊天上传附件"))
                        else -> {
                            call.application.log.warn("Encrypted attachment upload failed", error)
                            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("附件保存失败"))
                        }
                    }
                    return@post
                }
                stored.getOrThrow().forEach(EncryptedAttachmentStorage::delete)
                call.respond(
                    HttpStatusCode.Created,
                    AttachmentUploadResponse(attachmentId, received.sha256, received.byteCount, expiresAt)
                )
            }

            get("/api/attachments/{id}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // Bandwidth / bulk-exfil throttle (authenticated participants still rate-limited)
                if (!aiRateLimiter.acquire("attachment_download:$userId", maxPerMinute = 60)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("附件下载过于频繁，请稍后再试"))
                    return@get
                }
                val attachmentId = call.parameters["id"].orEmpty()
                val record = encryptedAttachmentRepo.get(attachmentId) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("附件不存在"))
                    return@get
                }
                if (record.expiresAt != null && record.expiresAt <= System.currentTimeMillis()) {
                    encryptedAttachmentRepo.removeUncommitted(attachmentId, record.uploaderId)
                    EncryptedAttachmentStorage.delete(attachmentId)
                    call.respond(HttpStatusCode.Gone, ErrorResponse("附件已过期"))
                    return@get
                }
                if (record.status != "COMMITTED") {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("附件尚未关联消息"))
                    return@get
                }
                if (!encryptedAttachmentRepo.isBoundToLiveMessage(attachmentId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("附件关联消息不存在"))
                    return@get
                }
                if (!conversationParticipantRepo.isParticipant(record.chatId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权下载该附件"))
                    return@get
                }
                // 与历史消息一致：双向拉黑语义（8.30 隐私修复）——viewer 拉黑了发送者，
                // 或发送者拉黑了 viewer，都不可下载其附件密文。
                val boundMessageId = record.messageId
                if (!boundMessageId.isNullOrBlank()) {
                    val senderId = com.maodouchat.server.messaging.v2.MessagingV2Repository()
                        .messageMetadata(boundMessageId)
                        ?.senderUserId
                    if (senderId != null && senderId != userId && userRepo.isBlockedEitherWay(userId, senderId)) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权下载该附件"))
                        return@get
                    }
                }
                val file = EncryptedAttachmentStorage.resolve(attachmentId)
                if (file == null || file.length() != record.cipherSize) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("附件密文不可用"))
                    return@get
                }
                call.response.header(ATTACHMENT_HASH_HEADER, record.cipherSha256)
                call.response.header(HttpHeaders.CacheControl, "private, no-store")
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=encrypted-attachment.bin")
                call.response.header("Accept-Ranges", "bytes")
                val rangeHeader = call.request.header("Range")
                // 9.151：多区段（含逗号）忽略回退全量（RFC 允许）；单区段非法/无法满足 → 416
                val range = rangeHeader?.takeIf { ',' !in it }?.let { parseAttachmentRange(it, file.length()) }
                if (rangeHeader != null && ',' !in rangeHeader && range == null) {
                    call.response.header("Content-Range", "bytes */${file.length()}")
                    call.respondText("", status = ATTACHMENT_RANGE_NOT_SATISFIABLE)
                    return@get
                }
                if (range == null) {
                    call.respondFile(file)
                } else {
                    val remaining = range.last - range.first + 1
                    call.response.header("Content-Range", "bytes ${range.first}-${range.last}/${file.length()}")
                    call.response.header(HttpHeaders.ContentLength, remaining)
                    call.respondOutputStream(
                        contentType = ContentType.Application.OctetStream,
                        status = HttpStatusCode.PartialContent
                    ) {
                        java.io.RandomAccessFile(file, "r").use { input ->
                            input.seek(range.first)
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var left = remaining
                            while (left > 0L) {
                                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                                if (read < 0) break
                                write(buffer, 0, read)
                                left -= read
                            }
                        }
                    }
                }
            }

            delete("/api/attachments/{id}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val attachmentId = call.parameters["id"].orEmpty()
                if (!encryptedAttachmentRepo.removeUncommitted(attachmentId, userId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("待确认附件不存在"))
                    return@delete
                }
                EncryptedAttachmentStorage.delete(attachmentId)
                call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }
            post("/api/auth/logout-all") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                authTokenRepo.rotateAccessTokenVersion(userId)
                // 全设备登出后必须清掉推送 token，否则已退出设备仍可能收到来电唤醒。
                pushTokenRepo.removeAllForUser(userId)
                disconnectUserSessions(userId, "已在其他设备退出全部会话")
                call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }

            get("/api/auth/totp/status") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                call.respond(TotpStatusResponse(enabled = userRepo.isTotpEnabled(userId)))
            }

            // 0.77：重新生成恢复码（验证当前 TOTP；旧码全部作废）
            post("/api/auth/totp/recover-codes") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (!totpManageRateLimiter.acquire(userId, maxPerMinute = 5)) {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                }
                val body = call.receiveBoundedTextOrEmpty()
                val code = runCatching {
                    Json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content
                }.getOrNull().orEmpty()
                val codes = userRepo.regenerateBackupCodes(userId, code)
                if (codes == null) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid totp code", code = "TOTP_INVALID"))
                }
                call.respond(TotpStatusResponse(enabled = true, backupCodes = codes))
            }

            post("/api/auth/totp/setup") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val setup = userRepo.beginTotpSetup(userId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("user not found"))
                call.respond(
                    TotpSetupResponse(
                        secret = setup.first,
                        otpauthUrl = setup.second,
                        enabled = false
                    )
                )
            }

            post("/api/auth/totp/confirm") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // 8.40：2FA 管理端点限流 + 失败锁定——6 位码 ±1 窗口可爆破，此前无限流
                if (!totpManageRateLimiter.acquire(userId, maxPerMinute = 5)) {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                }
                val body = call.receiveBoundedTextOrEmpty()
                val code = runCatching {
                    Json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content
                }.getOrNull().orEmpty()
                val backupCodes = userRepo.confirmTotpSetup(userId, code)
                if (backupCodes == null) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid totp code", code = "TOTP_INVALID"))
                }
                // 0.75：恢复码明文仅此一次返回（App 提示用户妥善保存）
                call.respond(TotpStatusResponse(enabled = true, backupCodes = backupCodes))
            }

            post("/api/auth/totp/disable") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // 8.40：与 confirm 一致限流；disable 需验码，爆破同样应被抑制
                if (!totpManageRateLimiter.acquire(userId, maxPerMinute = 5)) {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                }
                val body = call.receiveBoundedTextOrEmpty()
                val code = runCatching {
                    Json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content
                }.getOrNull().orEmpty()
                if (!userRepo.disableTotp(userId, code)) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid totp code", code = "TOTP_INVALID"))
                }
                call.respond(TotpStatusResponse(enabled = false))
            }


            // 用户 API
            get("/api/users/me") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val user = userRepo.getById(userId)
                if (user != null) call.respond(user)
                else call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
            }

            // P0 修复后续：推送 HMAC 签名密钥经认证通道下发（此前匿名暴露在 /api/public/status，
            // 任何客户端可拿密钥伪造 FCM 签名）。密钥按用户派生（HMAC(master, userId)），
            // 仅认证用户可取**自己的**派生密钥——只能伪造发给自己的推送，无法伪造他人。
            get("/api/push/verify-key") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val secret = com.maodouchat.server.config.ServerConfig.pushHmacSecret
                if (secret.isBlank() || secret.startsWith("dev-only-")) {
                    call.respond(buildJsonObject { put("key", JsonNull) })
                } else {
                    call.respond(buildJsonObject { put("key", com.maodouchat.server.service.FcmPushService.pushKeyForUser(userId)) })
                }
            }

            get("/api/users") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (!userSearchRateLimiter.acquire(userId, maxPerMinute = 30)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                    return@get
                }
                // 8.38：与 /api/users/search 一致截断 q 到 100（底层 LIKE 四列全表扫描）
                val q = call.request.queryParameters["q"]?.trim().orEmpty().take(100)
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 30).coerceIn(1, 100)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                if (q.isBlank()) {
                    call.respond(userRepo.getAll(limit, offset = offset, viewerId = userId))
                } else {
                    if (q.length < 2) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("搜索关键字至少 2 个字符"))
                        return@get
                    }
                    call.respond(userRepo.searchUsers(q, excludeUserId = userId, limit = limit, viewerId = userId))
                }
            }

            get("/api/users/search") {
                // 8.33 修复：q 截断到 100 字符（底层 LIKE 全表扫描，超长关键字无意义且放大成本）
                val q = call.request.queryParameters["q"]?.trim().orEmpty().take(100)
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 30).coerceIn(1, 100)
                if (q.length < 2) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("搜索关键字至少 2 个字符"))
                    return@get
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (!userSearchRateLimiter.acquire(userId, maxPerMinute = 30)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                    return@get
                }
                call.respond(userRepo.searchUsers(q, excludeUserId = userId, limit = limit, viewerId = userId))
            }

            get("/api/users/privacy") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val privacy = userRepo.getPrivacy(userId)
                if (privacy != null) call.respond(privacy)
                else call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
            }

            put("/api/users/privacy") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // 8.38：封禁用户不得改隐私（与头像/资料/附近位置一致，防关闭 searchable 逃避检索处置）
                if (call.rejectIfSuspended(userRepo, userId)) return@put
                val req = call.receiveBoundedText()?.let { parseJson<UpdatePrivacyRequest>(it) }
                if (req == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效")); return@put }
                if (req.defaultPostVisibility != null && !isValidPostVisibility(req.defaultPostVisibility)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("动态可见范围无效"))
                    return@put
                }
                val update = userRepo.updatePrivacyWithTransitions(
                    userId,
                    showOnline = req.showOnline,
                    showStatus = req.showStatus,
                    searchable = req.searchable,
                    defaultPostVisibility = req.defaultPostVisibility,
                    onlineVisibility = req.onlineVisibility
                )
                if (update == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
                    return@put
                }
                if (update.onlineRevoked || update.statusRevoked) {
                    broadcastUserVisibilityRevoked(
                        userId = userId,
                        onlineRevoked = update.onlineRevoked,
                        statusRevoked = update.statusRevoked,
                        json = json,
                        userRepo = userRepo
                    )
                }
                call.respond(update.privacy)
            }

            get("/api/users/nearby-location") {

                if (!RuntimeConfigService.isNearbyEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("nearby_disabled"))
                    return@get
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                call.respond(nearbyRepo.getStatus(userId))
            }

            put("/api/users/nearby-location") {

                if (!RuntimeConfigService.isNearbyEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("nearby_disabled"))
                    return@put
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // 8.33 修复：封禁用户不得更新附近位置（位置 = 实时行踪，封禁期间必须消失）
                if (call.rejectIfSuspended(userRepo, userId)) return@put
                // 每用户限流：位置更新是 DB 写，防高频轮询刷写
                if (!nearbyUpdateRateLimiter.acquire(userId, maxPerMinute = 10)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("位置更新太频繁，请稍后再试"))
                    return@put
                }
                val req = call.receiveBoundedText()?.let { parseJson<UpdateNearbyLocationRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("位置参数无效"))
                    return@put
                }
                val status = nearbyRepo.updateLocation(userId, req.latitude, req.longitude)
                if (status == null) call.respond(HttpStatusCode.BadRequest, ErrorResponse("位置参数无效"))
                else call.respond(status)
            }

            delete("/api/users/nearby-location") {

                if (!RuntimeConfigService.isNearbyEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("nearby_disabled"))
                    return@delete
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                nearbyRepo.stopSharing(userId)
                call.respond(NearbyLocationStatusResponse(false, 0))
            }

            get("/api/users/nearby") {

                if (!RuntimeConfigService.isNearbyEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("nearby_disabled"))
                    return@get
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // 8.33 修复：封禁用户不得查询附近的人（位置隐私双向一致）
                if (call.rejectIfSuspended(userRepo, userId)) return@get
                // 每用户限流：附近查询是范围扫描 + haversine 计算，防高频轮询打 CPU
                if (!nearbyQueryRateLimiter.acquire(userId, maxPerMinute = 30)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("查询太频繁，请稍后再试"))
                    return@get
                }
                val radiusKm = (call.request.queryParameters["radiusKm"]?.toDoubleOrNull() ?: 10.0).coerceIn(0.5, 30.0)
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
                call.respond(nearbyRepo.getNearby(userId, radiusKm, limit))
            }

            get("/api/users/notification-settings") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                call.respond(notificationPreferenceRepo.getSettings(userId))
            }

            put("/api/users/notification-settings") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val req = call.receiveBoundedText()?.let { parseJson<NotificationSettingsRequest>(it) }
                if (req == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@put
                }
                call.respond(notificationPreferenceRepo.updateSettings(userId, req))
            }

            post("/api/users/push-tokens") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.subject
                val authSessionId = JwtConfig.authSessionId(principal.payload)!!
                val req = call.receiveBoundedText()?.let { parseJson<RegisterPushTokenRequest>(it) }
                val deviceId = req?.deviceId?.trim().orEmpty()
                val token = req?.token?.trim().orEmpty()
                val platform = req?.platform?.trim()?.uppercase().orEmpty()
                if (req == null || !deviceId.matches(Regex("^[A-Za-z0-9._:-]{1,100}$")) ||
                    token.length !in 32..512 || token.any(Char::isWhitespace) ||
                    platform != "ANDROID" || req.timezoneOffsetMinutes !in -1080..1080
                ) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("推送令牌无效"))
                    return@post
                }
                if (!pushTokenRepo.register(
                        userId,
                        deviceId,
                        token,
                        platform,
                        req.timezoneOffsetMinutes,
                        authSessionId
                    )
                ) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("登录会话已被撤销"))
                    return@post
                }
                call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }

            delete("/api/users/push-tokens") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val req = call.receiveBoundedText()?.let { parseJson<RemovePushTokenRequest>(it) }
                val deviceId = req?.deviceId?.trim().orEmpty()
                if (!deviceId.matches(Regex("^[A-Za-z0-9._:-]{1,100}$"))) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("设备标识无效"))
                    return@delete
                }
                pushTokenRepo.remove(userId, deviceId)
                call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }

            get("/api/users/{id}") {
                val viewerId = call.principal<JWTPrincipal>()?.payload?.subject
                val user = userRepo.getPublicById(call.parameters["id"]!!, viewerId = viewerId)
                if (user != null) {
                    call.respond(
                        user.copy(isOnline = user.isOnline && userRepo.shouldShowOnlineTo(user.id, viewerId))
                    )
                } else call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
            }

            // 上传头像
            post("/api/users/avatar") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // 8.33 修复：封禁用户不得更换头像/资料（与 profile 修改一致）
                if (call.rejectIfSuspended(userRepo, userId)) return@post
                if (!avatarRateLimiter.acquire(userId, maxPerMinute = 10)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("头像操作过于频繁，请稍后再试"))
                    return@post
                }
                val req = call.receiveBoundedText(MAX_UPLOAD_JSON_BODY_CHARS)?.let { parseJson<UploadAvatarRequest>(it) }
                if (req == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效")); return@post }
                val avatarUrl = try {
                    com.maodouchat.server.service.FileStorageService.saveAvatar(req.base64Data, userId)
                } catch (e: IllegalArgumentException) {
                    // 不把内部校验明细回传给客户端，仅服务端日志保留上下文
                    call.application.log.warn("Avatar upload rejected for user {}: {}", userId, e.message)
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("头像数据无效"))
                    return@post
                }
                val replacement = try {
                    userRepo.replaceAvatar(userId, avatarUrl)
                } catch (error: Throwable) {
                    com.maodouchat.server.service.FileStorageService.deleteAvatarUrl(avatarUrl, userId)
                    throw error
                }
                if (replacement == null) {
                    com.maodouchat.server.service.FileStorageService.deleteAvatarUrl(avatarUrl, userId)
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
                    return@post
                }
                if (replacement.previousUrl != replacement.currentUrl) {
                    com.maodouchat.server.service.FileStorageService.deleteAvatarUrl(replacement.previousUrl, userId)
                }
                call.respond(
                buildJsonObject {
put("status", "ok")
put("avatarUrl", avatarUrl)
                }
            )
            }

            delete("/api/users/avatar") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val replacement = userRepo.replaceAvatar(userId, null)
                if (replacement == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
                    return@delete
                }
                com.maodouchat.server.service.FileStorageService.deleteAvatarUrl(replacement.previousUrl, userId)
                call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }

            // 修改资料
            put("/api/users/profile") {
                val userId = call.principal<JWTPrincipal>()?.payload?.subject
                if (userId == null) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("未认证")); return@put }
                // 8.33 修复：封禁用户不得修改资料（此前仅部分写路径有检查）
                if (call.rejectIfSuspended(userRepo, userId)) return@put
                val req = call.receiveBoundedText()?.let { parseJson<UpdateProfileRequest>(it) }
                if (req == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效")); return@put }
                val before = userRepo.getById(userId)
                userRepo.updateProfile(userId, name = req.name, status = req.status)
                val updated = userRepo.getById(userId)
                // 0.93：资料变更失效公开主页缓存
                before?.username?.let { cacheService.invalidateUserProfile("user_profile:$it") }
                if (updated != null) call.respond(updated)
                else call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
            }

            // 设置用户名（类似 @username，用于 chat.mdou.me/u/{username}）
            put("/api/users/me/username") {
                val userId = call.principal<JWTPrincipal>()?.payload?.subject
                if (userId == null) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("未认证")); return@put }
                // 8.38：封禁用户不得改用户名（与头像/资料/附近位置一致）；且设置带限流防占用枚举
                if (call.rejectIfSuspended(userRepo, userId)) return@put
                if (!userSearchRateLimiter.acquire("username:$userId", maxPerMinute = 10)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                    return@put
                }
                val obj = call.receiveBoundedText()?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                val username = obj["username"]?.jsonPrimitive?.content.orEmpty().trim().lowercase()
                // 8.40：格式非法 400、已占用 409 分离（此前一律 409，客户端无法区分参数错误与冲突）
                if (username.length !in 3..50 || !username.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("用户名格式无效（3-50 位字母/数字/_/-）"))
                }
                val result = userRepo.setUsername(userId, username)
                if (result != null) {
                    // 0.93：用户名变更失效旧/新公开主页缓存
                    cacheService.invalidateUserProfile("user_profile:$username")
                    call.respond(
                buildJsonObject {
put("ok", true)
put("username", result)
                }
            )
                } else {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("用户名不可用（已占用或格式无效）"))
                }
            }

            // 清除用户名
            delete("/api/users/me/username") {
                val userId = call.principal<JWTPrincipal>()?.payload?.subject
                if (userId == null) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("未认证")); return@delete }
                if (call.rejectIfSuspended(userRepo, userId)) return@delete
                userRepo.clearUsername(userId)
                call.respond(
                buildJsonObject {
put("ok", true)
                }
            )
            }

            // 获取当前用户公开信息（含用户名；8.32 一致性：公开形态不含 email 等私有字段）
            get("/api/users/me/public") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val user = userRepo.getPublicMe(userId)
                if (user != null) {
                    val publicProfileUrl = user.username?.let { "${ServerConfig.baseUrl.trimEnd('/')}/u/${it}" }
                    call.respond(
                buildJsonObject {
put("user", Json.parseToJsonElement(Json.encodeToString(user)))
put("publicProfileUrl", publicProfileUrl)
                }
            )
                } else call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
            }

            // 修改密码：成功后吊销全部刷新令牌并轮换 access token version，避免旧会话继续有效
            post("/api/users/change-password") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val req = call.receiveBoundedText()?.let { parseJson<ChangePasswordRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@post
                }
                if (req.oldPassword.isBlank() || !isValidPassword(req.newPassword)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("新密码至少 6 位"))
                    return@post
                }
                val ok = userRepo.changePassword(userId, req.oldPassword, req.newPassword)
                if (ok) {
                    authTokenRepo.rotateAccessTokenVersion(userId)
                    // 与 logout-all 一致：旧设备会话已废，推送 token 必须清掉，否则仍收消息/来电推送
                    pushTokenRepo.removeAllForUser(userId)
                    disconnectUserSessions(userId, "密码已修改，请重新登录")
                    call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
                } else {
                    // 403：勿用 401 — 客户端 executeWithRefresh 会把带 Authorization 的 401 当会话过期并清库
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("原密码错误", code = "WRONG_PASSWORD"))
                }
            }

            delete("/api/users/me") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val groupAvatarCandidates = groupMediaReferenceRepo.avatarUrlsForParticipant(userId)
                // 8.33 修复：删号会 bump memberRevision（含群主转让），但此前无广播，剩余成员残留成员列表
                val groupSnapshots = conversationParticipantRepo.groupMembershipSnapshotForDeletion(userId)
                val req = call.receiveBoundedText()?.let { parseJson<DeleteAccountRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@delete
                }
                if (req.password.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("请输入当前密码"))
                    return@delete
                }
                val deactivation = userRepo.deleteAccount(userId, req.password)
                if (deactivation == null) {
                    // 403：凭证错误，非 access token 失效
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("密码错误或账号已注销", code = "WRONG_PASSWORD"))
                } else {
                    // 空会话级联删除的附件行已不在 DB；先清磁盘，再清仍挂在其他会话上的本人上传
                    deactivation.orphanedAttachmentIds.forEach(EncryptedAttachmentStorage::delete)
                    encryptedAttachmentRepo.deleteForUploader(userId).forEach(EncryptedAttachmentStorage::delete)
                    postRepo.deleteAllPostsForAuthor(userId)
                    com.maodouchat.server.service.FileStorageService.deletePostImagesForUser(userId)
                    groupAvatarCandidates
                        .filterNot(groupMediaReferenceRepo::isAvatarUrlReferenced)
                        .forEach { url ->
                            com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(url)
                        }
                    com.maodouchat.server.service.FileStorageService.deleteAvatarUrl(deactivation.avatarUrl, userId)
                    // 与 logout-all / 改密码一致：吊销已签发的 access token（版本号）并清推送 token，
                    // 否则注销后旧 JWT 在 TTL 内仍可调用 API；推送 token 不清则已注销设备仍收消息/来电。
                    authTokenRepo.rotateAccessTokenVersion(userId)
                    pushTokenRepo.removeAllForUser(userId)
                    disconnectUserSessions(userId, "账号已注销")
                    // 8.33：注销后向各群剩余成员广播成员变更（含自动群主转让），客户端即时刷新成员列表
                    groupSnapshots.forEach { (chatId, recipients) ->
                        val remaining = recipients.filter { it != userId }
                        if (remaining.isNotEmpty()) {
                            notifyGroupRevisionChanged(
                                queryRepository = conversationQueryRepo,
                                participantRepository = conversationParticipantRepo,
                                json = json,
                                chatId = chatId,
                                reason = "MEMBER_REMOVED",
                                actorId = userId,
                                targetUserId = userId,
                                recipientIds = remaining
                            )
                        }
                    }
                    call.respond(DeleteAccountResponse(deletedAt = deactivation.deletedAt))
                }
            }

            // ─── Group polls ─────────────────────────────────────────
            post("/api/chats/{chatId}/polls") {
                if (!RuntimeConfigService.isPollsEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("polls_disabled"))
                    return@post
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // 9.145：封禁用户不得参与群玩法写入（与 PollRouting 各写端点的 8.33 口径一致——
                // 此前本文件的 polls 三写端点只查成员/禁言，封禁账号仍可创建投票广播到全群）
                if (call.rejectIfSuspended(userRepo, userId)) return@post
                val chatId = call.parameters["chatId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing chatId"))
                val body = call.receiveBoundedTextOrEmpty(32_768)
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                val question = obj["question"]?.jsonPrimitive?.content.orEmpty()
                // 9.157：与投票选项一致——非法元素整体拒绝，不静默截成子集
                val options = buildList {
                    val arr = obj["options"]?.jsonArray
                    if (arr != null) {
                        for (element in arr) {
                            val text = (element as? kotlinx.serialization.json.JsonPrimitive)?.content
                            if (text == null) {
                                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("投票选项无效"))
                            }
                            add(text)
                        }
                    }
                }
                val multi = obj["multi"]?.jsonPrimitive?.booleanOrNull
                    ?: obj["multi"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                    ?: false
                val anonymous = obj["anonymous"]?.jsonPrimitive?.booleanOrNull
                    ?: obj["anonymous"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                    ?: false
                val closesAt = obj["closesAt"]?.jsonPrimitive?.content?.toLongOrNull()
                // 8.32 一致性：非成员 403（与群管理端点一致），其余失败保持 400
                if (!com.maodouchat.server.repository.PollRepository.isMember(chatId, userId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
                }
                if (com.maodouchat.server.repository.PollRepository.isMuted(chatId, userId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("你已被禁言，暂时无法参与群玩法"))
                }
                val poll = com.maodouchat.server.repository.GroupPlayRepository.createPoll(
                    chatId, userId, question, options, multi, anonymous, closesAt
                ) ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("无法创建投票"))
                call.respond(poll)
            }
            get("/api/chats/{chatId}/polls") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val chatId = call.parameters["chatId"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing chatId"))
                // 8.32 一致性：非成员 403（此前仓库层静默过滤返回 200 []，与其余群资源 403 不一致）
                if (!com.maodouchat.server.repository.PollRepository.isMember(chatId, userId)) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该群"))
                }
                call.respond(com.maodouchat.server.repository.GroupPlayRepository.listChatPolls(chatId, userId))
            }
            get("/api/polls/{pollId}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val pollId = call.parameters["pollId"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing pollId"))
                val poll = com.maodouchat.server.repository.GroupPlayRepository.getPoll(pollId, userId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("poll not found"))
                call.respond(poll)
            }
            post("/api/polls/{pollId}/vote") {
                if (!RuntimeConfigService.isPollsEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("polls_disabled"))
                    return@post
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // 9.145：封禁用户不得参与投票（同 polls 创建口径）
                if (call.rejectIfSuspended(userRepo, userId)) return@post
                val pollId = call.parameters["pollId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing pollId"))
                val body = call.receiveBoundedTextOrEmpty(8_192)
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                // 9.157：严格解析——此前 mapNotNull 静默丢弃非法元素（如 [0,"abc",1] 被投成 [0,1]，
                // 用户发送垃圾数据却按子集成功投票）。任一元素非非负整数即整体拒绝。
                val indexes = buildList {
                    val arr = obj["optionIndexes"]?.jsonArray
                    if (arr != null) {
                        for (element in arr) {
                            val v = (element as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                            if (v == null || v < 0) {
                                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("投票选项无效"))
                            }
                            add(v)
                        }
                    } else {
                        val single = (obj["optionIndex"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                        if (single == null || single < 0) {
                            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("投票选项无效"))
                        }
                        add(single)
                    }
                }
                com.maodouchat.server.repository.GroupPlayRepository.getPoll(pollId, userId)?.let { existing ->
                    if (com.maodouchat.server.repository.PollRepository.isMuted(existing.chatId, userId)) {
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("你已被禁言，暂时无法参与群玩法"))
                    }
                }
                val poll = com.maodouchat.server.repository.GroupPlayRepository.vote(pollId, userId, indexes)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("投票失败"))
                call.respond(poll)
            }
            post("/api/polls/{pollId}/close") {
                if (!RuntimeConfigService.isPollsEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("polls_disabled"))
                    return@post
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                // 9.145：封禁用户不得关闭投票（同 polls 创建口径）
                if (call.rejectIfSuspended(userRepo, userId)) return@post
                val pollId = call.parameters["pollId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing pollId"))
                val poll = com.maodouchat.server.repository.GroupPlayRepository.closePoll(pollId, userId)
                    ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("无法关闭投票"))
                call.respond(poll)
            }

            // ─── Developer bots ───────────────────────────────────────
            
            
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
        authenticate("auth-jwt") {
            // 屏蔽
            post("/api/users/block/{uid}") {
                if (!RuntimeConfigService.isBlockReportEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("block_report_disabled"))
                    return@post
                }
                val blockerId = call.principal<JWTPrincipal>()!!.payload.subject
                val blockedId = call.parameters["uid"].orEmpty()
                if (!userRepo.blockUser(blockerId, blockedId)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("无法拉黑该用户"))
                    return@post
                }
                call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }
            delete("/api/users/block/{uid}") { userRepo.unblockUser(call.principal<JWTPrincipal>()!!.payload.subject, call.parameters["uid"]!!); call.respond(
                buildJsonObject {
put("status", "ok")
                }
            ) }
            get("/api/users/blocks") { call.respond(userRepo.getBlockedUsers(call.principal<JWTPrincipal>()!!.payload.subject)) }
            get("/api/users/blocks/details") { call.respond(userRepo.getBlockedUserDetails(call.principal<JWTPrincipal>()!!.payload.subject)) }
            post("/api/reports") {

                if (!RuntimeConfigService.isBlockReportEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("block_report_disabled"))
                    return@post
                }
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!reportRateLimiter.acquire(uid, maxPerMinute = 5)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("举报过于频繁，请稍后再试"))
                    return@post
                }
                val req = call.receiveBoundedText()?.let { parseJson<CreateReportRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@post
                }
                when (val result = reportRepo.createReport(uid, req)) {
                    is ReportRepository.CreateResult.Success -> call.respond(HttpStatusCode.Created, result.report)
                    is ReportRepository.CreateResult.Failure -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
                }
            }

            get("/api/reports/mine") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
                call.respond(reportRepo.getMyReports(uid, limit))
            }

            // 路径从 /api/admin/reports 改为 /api/moderator/reports，避免与 AdminRouting.kt 的 master admin 版本冲突
            // admin.js（web admin，admin-jwt）仍走 /api/admin/reports；app 客户端（moderator，auth-jwt）走此路径
            get("/api/moderator/reports") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!hasContentModerationAccess(userRepo, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                    return@get
                }
                val status = call.request.queryParameters["status"]
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 200)
                val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                call.respond(reportRepo.getReports(status, limit, offset))
            }

            put("/api/moderator/reports/{reportId}/status") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!hasContentModerationAccess(userRepo, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                    return@put
                }
                val reportId = call.parameters["reportId"].orEmpty()
                val req = call.receiveBoundedText()?.let { parseJson<UpdateReportStatusRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@put
                }
                when (val result = reportRepo.updateReportStatus(reportId, uid, req.status, req.resolutionNote)) {
                    is ReportRepository.UpdateResult.Success -> call.respond(result.report)
                    is ReportRepository.UpdateResult.Failure -> {
                        // 8.42：资源不存在 404、状态冲突 409 与参数错误 400 分离
                        val status = when (result.message) {
                            "举报不存在" -> HttpStatusCode.NotFound
                            "已处置的举报不能变更状态" -> HttpStatusCode.Conflict
                            else -> HttpStatusCode.BadRequest
                        }
                        call.respond(status, ErrorResponse(result.message))
                    }
                }
            }

            post("/api/moderator/reports/{reportId}/action") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!hasContentModerationAccess(userRepo, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                    return@post
                }
                val reportId = call.parameters["reportId"].orEmpty()
                val req = call.receiveBoundedText()?.let { parseJson<ApplyReportActionRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@post
                }
                val action = req.action.trim().uppercase()
                val existingReport = reportRepo.getReport(reportId) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("举报不存在"))
                    return@post
                }
                // 只读校验在标记前完成；处置对象 userId 在此冻结，避免 mark 后内容被删导致限制落空
                val frozenRestrictionTargetUserId: String? = when (action) {
                    "NO_ACTION" -> null
                    "DELETE_CONTENT" -> {
                        if (existingReport.targetType !in setOf("MESSAGE", "POST", "COMMENT")) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("该举报类型不能删除内容"))
                            return@post
                        }
                        null
                    }
                    "RESTRICT_MESSAGES_24H", "RESTRICT_POSTS_7D", "SUSPEND_24H" -> {
                        val targetUserId = when (existingReport.targetType) {
                            "USER" -> existingReport.targetId
                            "MESSAGE" -> com.maodouchat.server.messaging.v2.MessagingV2Repository()
                                .messageMetadata(existingReport.messageId ?: existingReport.targetId)
                                ?.senderUserId
                            "POST" -> postRepo.getPostAuthorId(existingReport.targetId)
                            "COMMENT" -> postRepo.getCommentAuthorId(existingReport.targetId)
                            else -> null
                        }
                        if (targetUserId.isNullOrBlank()) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("无法定位被处置用户"))
                            return@post
                        }
                        if (targetUserId == uid) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能处置自己"))
                            return@post
                        }
                        if (hasContentModerationAccess(userRepo, targetUserId)) {
                            call.respond(HttpStatusCode.Forbidden, ErrorResponse("不能通过举报处置审核员或超级管理员账号"))
                            return@post
                        }
                        targetUserId
                    }
                    else -> {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("处置动作无效"))
                        return@post
                    }
                }
                // 原子标记：仅 Applied 时执行副作用，避免双点重复封禁/删内容
                when (val mark = reportRepo.markActionTaken(reportId, uid, action, req.resolutionNote)) {
                    is ReportRepository.ActionMarkResult.Failure -> {
                        val status = if (mark.message == "举报不存在") HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                        call.respond(status, ErrorResponse(mark.message))
                        return@post
                    }
                    is ReportRepository.ActionMarkResult.AlreadyDone -> {
                        call.respond(mark.report)
                        return@post
                    }
                    is ReportRepository.ActionMarkResult.Applied -> {
                        val report = mark.report
                        when (action) {
                            "NO_ACTION" -> Unit
                            "DELETE_CONTENT" -> {
                                when (report.targetType) {
                                    "MESSAGE" -> {
                                        val messageId = report.messageId ?: report.targetId
                                        val deleted = com.maodouchat.server.messaging.v2.MessagingV2Repository()
                                            .deleteMessageForModeration(messageId)
                                        if (deleted != null) {
                                            deleted.deletedAttachmentIds.forEach(EncryptedAttachmentStorage::delete)
                                            fanoutSystemDelete(
                                                conversationParticipantRepo,
                                                json,
                                                deleted.metadata.conversationId,
                                                messageId,
                                            )
                                        }
                                    }
                                    "POST" -> {
                                        postRepo.deletePostForModeration(report.targetId)
                                        broadcastPostDeleted(report.targetId)
                                    }
                                    "COMMENT" -> postRepo.deleteCommentForModeration(report.targetId)
                                    else -> Unit
                                }
                            }
                            "RESTRICT_MESSAGES_24H", "RESTRICT_POSTS_7D", "SUSPEND_24H" -> {
                                val targetUserId = frozenRestrictionTargetUserId
                                if (!targetUserId.isNullOrBlank() &&
                                    targetUserId != uid &&
                                    !hasContentModerationAccess(userRepo, targetUserId)
                                ) {
                                    userRepo.applyModerationRestriction(targetUserId, action)
                                    if (action == "SUSPEND_24H") {
                                        authTokenRepo.rotateAccessTokenVersion(targetUserId)
                                        pushTokenRepo.removeAllForUser(targetUserId)
                                        disconnectUserSessions(targetUserId, "账号已被临时封禁")
                                    }
                                }
                            }
                        }
                        call.respond(report)
                    }
                }
            }

            get("/api/admin/moderation/rules") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!hasContentModerationAccess(userRepo, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                    return@get
                }
                call.respond(moderationRuleRepo.getRules())
            }

            put("/api/admin/moderation/rules/{ruleId}") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!hasContentModerationAccess(userRepo, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                    return@put
                }
                val ruleId = call.parameters["ruleId"].orEmpty()
                val req = call.receiveBoundedText()?.let { parseJson<UpdateModerationRuleRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                    return@put
                }
                // 8.32 一致性：资源不存在 404、参数问题 400（此前合并为一个 400）
                if (!moderationRuleRepo.ruleExists(ruleId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("规则不存在"))
                    return@put
                }
                val updated = moderationRuleRepo.updateRule(ruleId, req)
                if (updated == null) call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                else call.respond(updated)
            }

            get("/api/admin/moderation/events") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!hasContentModerationAccess(userRepo, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                    return@get
                }
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 200)
                val needsReview = call.request.queryParameters["needsReview"]?.toBooleanStrictOrNull()
                call.respond(moderationRuleRepo.getRiskEvents(limit, needsReview))
            }

            post("/api/admin/moderation/events/{eventId}/ack") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!hasContentModerationAccess(userRepo, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                    return@post
                }
                val eventId = call.parameters["eventId"].orEmpty()
                if (!moderationRuleRepo.acknowledgeRiskEvent(eventId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("风险事件不存在"))
                    return@post
                }
                call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }

            // Cloud chat inference, /api/ai/settings and summary-sync are gone.
            // Server AI is post/comment moderation only (AiGateway.classifyContent).
            // Chat plaintext inference used to live here (/api/ai/rewrite, summarize, analyze-*).
            // Those endpoints are removed; clients use a user-configured model on-device.

            // 星标消息
            post("/api/messages/{messageId}/star") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!com.maodouchat.server.service.RuntimeConfigService.isMessageStarringEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("starring_disabled"))
                    return@post
                }
                val mid = call.parameters["messageId"]!!
                val starred = starMessageRepo.toggleStar(uid, mid)
                if (starred == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("该消息不能星标"))
                    return@post
                }
                call.respond(buildJsonObject { put("status", "ok"); put("starred", starred) })
            }

            // 会话消息置顶（群：管理员；单聊：双方）
            get("/api/chats/{chatId}/pins") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val chatId = call.parameters["chatId"]!!
                if (!conversationParticipantRepo.isParticipant(chatId, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
                    return@get
                }
                call.respond(
                    PinnedMessagesListResponse(
                        chatId = chatId,
                        pins = pinnedMessageRepo.list(chatId)
                    )
                )
            }
            post("/api/chats/{chatId}/messages/{messageId}/pin") {
                if (call.rejectIfMaintenance()) return@post
                if (!RuntimeConfigService.isMessagePinEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("message_pin_disabled"))
                    return@post
                }
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfSuspended(userRepo, uid)) return@post
                val chatId = call.parameters["chatId"]!!
                val mid = call.parameters["messageId"]!!
                if (!conversationParticipantRepo.isParticipant(chatId, uid)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权操作"))
                    return@post
                }
                val chat = conversationQueryRepo.getById(chatId)
                if (chat == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("聊天不存在"))
                    return@post
                }
                val actorIsManager = if (chat.isGroup) conversationParticipantRepo.isOwnerOrAdmin(chatId, uid) else true
                val outcome = pinnedMessageRepo.toggle(
                    chatId = chatId,
                    messageId = mid,
                    actorId = uid,
                    actorIsManager = actorIsManager
                )
                when (outcome.result) {
                    PinnedMessageRepository.PinResult.NOT_FOUND -> {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("消息不存在"))
                        return@post
                    }
                    PinnedMessageRepository.PinResult.FORBIDDEN -> {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("仅群主或管理员可置顶"))
                        return@post
                    }
                    PinnedMessageRepository.PinResult.LIMIT -> {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("最多置顶 ${PinnedMessageRepository.MAX_PINS_PER_CHAT} 条消息")
                        )
                        return@post
                    }
                    PinnedMessageRepository.PinResult.NOT_PINNABLE -> {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("该消息不能置顶"))
                        return@post
                    }
                    PinnedMessageRepository.PinResult.PINNED,
                    PinnedMessageRepository.PinResult.UNPINNED -> {
                        val pinned = outcome.result == PinnedMessageRepository.PinResult.PINNED
                        val payload = PinnedMessagesUpdatedPayload(chatId, uid, outcome.pins)
                        val pinJson = json.encodeToString(
                            WsMessage.serializer(),
                            WsMessage(
                                "PINNED_MESSAGES_UPDATED",
                                json.encodeToString(PinnedMessagesUpdatedPayload.serializer(), payload)
                            )
                        )
                        conversationParticipantRepo.participantIds(chatId).forEach { participantId ->
                            sendToUser(participantId, pinJson)
                        }
                        call.respond(
                            TogglePinResponse(
                                status = "ok",
                                pinned = pinned,
                                pins = outcome.pins
                            )
                        )
                    }
                }
            }
            get("/api/messages/starred") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                if (!com.maodouchat.server.service.RuntimeConfigService.isMessageStarringEnabled()) {
                    call.respond(emptyList<com.maodouchat.server.model.StarredMessageReference>())
                    return@get
                }
                val chatId = call.request.queryParameters["chatId"]
                call.respond(starMessageRepo.getStarredMessages(uid, chatId))
            }

            // 发现页 / 动态 API
            get("/api/posts") {

                if (!RuntimeConfigService.isPostsEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("posts_disabled"))
                    return@get
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 30).coerceIn(1, 50)
                val before = call.request.queryParameters["before"]?.toLongOrNull()
                val beforeId = call.request.queryParameters["beforeId"]
                    ?.takeIf { before != null && it.isNotBlank() && it.length <= 100 }
                val authorId = call.request.queryParameters["authorId"]
                if (authorId != null) {
                    call.respond(postRepo.getPostsByAuthor(userId, authorId, limit, before, beforeId))
                } else {
                    call.respond(postRepo.getFeed(userId, limit, before, beforeId))
                }
            }

            post("/api/posts") {

                if (!RuntimeConfigService.isPostsEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("posts_disabled"))
                    return@post
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfPostRestricted(userRepo, userId)) return@post
                if (!postRateLimiter.acquire(userId, maxPerMinute = 20)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("发布过于频繁，请稍后再试"))
                    return@post
                }
                val req = call.receiveBoundedText()?.let { parseJson<CreatePostRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("动态内容无效"))
                    return@post
                }
                // Legacy clients always sent PUBLIC even when it only represented the UI default.
                // Preserve explicit CONTACTS/PRIVATE choices while failing closed for legacy PUBLIC.
                val useAccountDefault = req.useDefaultVisibility
                    ?: (req.visibility == null || req.visibility == "PUBLIC")
                val visibility = if (useAccountDefault) {
                    userRepo.getPrivacy(userId)?.defaultPostVisibility ?: "PRIVATE"
                } else {
                    req.visibility ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("动态可见性无效"))
                        return@post
                    }
                }
                if (!isValidPostPayload(req.content, req.imageUrls, visibility)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("动态内容无效"))
                    return@post
                }
                if (req.imageUrls.any { url ->
                        !com.maodouchat.server.service.FileStorageService.isOwnedPostImageUrl(url, userId)
                    }
                ) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("动态图片无效或不属于当前账号"))
                    return@post
                }
                val postPlain = buildString {
                    append(req.content.trim())
                    if (req.imageUrls.isNotEmpty()) append('\n').append(req.imageUrls.joinToString("\n"))
                }
                val keywordModeration = moderationRuleRepo.evaluate(
                    userId = userId,
                    source = "POST",
                    content = postPlain
                )
                val moderation = ContentModerationService.combine(
                    userId = userId,
                    source = "POST",
                    content = postPlain,
                    keyword = keywordModeration,
                    gateway = aiGateway,
                    rules = moderationRuleRepo
                )
                if (moderation.blocked) {
                    val status = if (moderation.action == "AUTO_RATE_LIMIT") HttpStatusCode.TooManyRequests else HttpStatusCode.UnprocessableEntity
                    call.respond(status, ErrorResponse(moderation.message ?: "内容未通过安全检查"))
                    return@post
                }
                val created = try {
                    postRepo.createPost(userId, req.content.trim(), req.imageUrls, visibility)
                } catch (error: IllegalArgumentException) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(error.message ?: "动态图片已被使用"))
                    return@post
                }
                moderation.matches.mapNotNull { it.eventId }.takeIf { it.isNotEmpty() }?.let { ids ->
                    moderationRuleRepo.attachReference(ids, created.id)
                }
                call.respond(HttpStatusCode.Created, created)
            }

            post("/api/posts/images") {

                if (!RuntimeConfigService.isPostsEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("posts_disabled"))
                    return@post
                }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfPostRestricted(userRepo, userId)) return@post
                if (!postImageRateLimiter.acquire(userId, maxPerMinute = 10)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("图片上传过于频繁，请稍后再试"))
                    return@post
                }
                val req = call.receiveBoundedText(MAX_UPLOAD_JSON_BODY_CHARS)?.let { parseJson<UploadPostImageRequest>(it) }
                if (req == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效")); return@post }
                val imageUrl = try {
                    com.maodouchat.server.service.FileStorageService.savePostImage(req.base64Data, userId)
                } catch (e: IllegalArgumentException) {
                    // 不把内部校验明细回传给客户端，仅服务端日志保留上下文
                    call.application.log.warn("Post image upload rejected for user {}: {}", userId, e.message)
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("图片数据无效"))
                    return@post
                }
                call.respond(UploadPostImageResponse("ok", imageUrl))
            }

            delete("/api/posts/images/{filename}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val filename = call.parameters["filename"].orEmpty()
                if (!com.maodouchat.server.service.FileStorageService.isOwnedPostImageFilename(filename, userId)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("动态图片无效"))
                    return@delete
                }
                postRepo.deleteUnclaimedPostImage(filename, userId)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/api/posts/{id}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val postId = call.parameters["id"]!!
                val post = postRepo.getPostById(postId, userId)
                if (post == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                else call.respond(post)
            }

            delete("/api/posts/{id}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val postId = call.parameters["id"]!!
                if (!postRepo.exists(postId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                    return@delete
                }
                if (!postRepo.isAuthor(postId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权删除该动态"))
                    return@delete
                }
                postRepo.deletePost(postId, userId)
                broadcastPostDeleted(postId, actorId = userId)
                call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }

            put("/api/posts/{id}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val postId = call.parameters["id"]!!
                if (!postRepo.exists(postId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                    return@put
                }
                if (!postRepo.isAuthor(postId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权编辑该动态"))
                    return@put
                }
                val req = call.receiveBoundedText()?.let { parseJson<EditPostRequest>(it) } ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求体无效"))
                    return@put
                }
                val newContent = req.content.trim()
                if (newContent.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("动态内容不能为空"))
                    return@put
                }
                if (newContent.length > MAX_POST_CONTENT_LENGTH) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("动态内容超出长度限制"))
                    return@put
                }
                if (req.visibility != null && !isValidPostVisibility(req.visibility)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("可见范围无效"))
                    return@put
                }
                // 8.38：编辑动态同样过内容审核（发动态/评论均过，编辑此前绕过——
                // 已发布内容可借编辑改成触发规则的内容）
                val keywordModeration = moderationRuleRepo.evaluate(
                    userId = userId,
                    source = "POST",
                    content = newContent
                )
                val moderation = ContentModerationService.combine(
                    userId = userId,
                    source = "POST",
                    content = newContent,
                    keyword = keywordModeration,
                    gateway = aiGateway,
                    rules = moderationRuleRepo
                )
                if (moderation.blocked) {
                    val status = if (moderation.action == "AUTO_RATE_LIMIT") HttpStatusCode.TooManyRequests else HttpStatusCode.UnprocessableEntity
                    call.respond(status, ErrorResponse(moderation.message ?: "内容未通过安全检查"))
                    return@put
                }
                val updated = postRepo.updatePost(postId, userId, newContent, req.visibility)
                if (updated == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在或更新失败"))
                    return@put
                }
                moderation.matches.mapNotNull { it.eventId }.takeIf { it.isNotEmpty() }?.let { ids ->
                    moderationRuleRepo.attachReference(ids, updated.id)
                }
                call.respond(updated)
            }

            post("/api/posts/{id}/like") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfSuspended(userRepo, userId)) return@post
                val postId = call.parameters["id"]!!
                // 8.38：点赞/取消点赞限流——此前无限流可对作者反复 like/unlike 刷 FCM 通知
                if (!postLikeRateLimiter.acquire(userId, maxPerMinute = 30)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                    return@post
                }
                // 1.137：禁止给自己的动态点赞
                if (postRepo.getPostAuthorId(postId) == userId) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能给自己的动态点赞"))
                    return@post
                }
                val wasAlreadyLiked = postRepo.hasLiked(postId, userId)
                if (!postRepo.likePost(postId, userId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                    return@post
                }
                val post = postRepo.getPostById(postId, userId)
                postRepo.getPostAuthorId(postId)?.let { authorId ->
                    if (!wasAlreadyLiked && !userRepo.isBlockedEitherWay(authorId, userId)) {
                        pushService.enqueuePostInteraction(authorId, userId, postId, "LIKE")
                    }
                }
                if (post != null) call.respond(post) else call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }

            delete("/api/posts/{id}/like") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val postId = call.parameters["id"]!!
                if (!postLikeRateLimiter.acquire(userId, maxPerMinute = 30)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                    return@delete
                }
                if (!postRepo.unlikePost(postId, userId)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                    return@delete
                }
                val post = postRepo.getPostById(postId, userId)
                if (post != null) call.respond(post) else call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }

            get("/api/posts/{id}/comments") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val postId = call.parameters["id"]!!
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
                val before = call.request.queryParameters["before"]?.toLongOrNull()
                val beforeId = call.request.queryParameters["beforeId"]
                    ?.takeIf { before != null && it.isNotBlank() && it.length <= 100 }
                val comments = postRepo.getComments(postId, userId, limit, before, beforeId)
                if (comments == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                else call.respond(comments)
            }

            // 1.93：动态点赞者列表
            get("/api/posts/{id}/likers") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val postId = call.parameters["id"]!!
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
                val likers = postRepo.listPostLikers(postId, userId, limit)
                if (likers == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                else call.respond(PostLikersResponse(postId, likers))
            }

            post("/api/posts/{id}/comments") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfPostRestricted(userRepo, userId)) return@post
                if (!commentRateLimiter.acquire(userId, maxPerMinute = 30)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("评论过于频繁，请稍后再试"))
                    return@post
                }
                val postId = call.parameters["id"]!!
                val req = call.receiveBoundedText()?.let { parseJson<CreateCommentRequest>(it) }
                if (req == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效")); return@post }
                if (!isValidCommentPayload(req.content)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("评论内容无效"))
                    return@post
                }
                val commentPlain = req.content.trim()
                val keywordModeration = moderationRuleRepo.evaluate(userId, "COMMENT", commentPlain)
                val moderation = ContentModerationService.combine(
                    userId = userId,
                    source = "COMMENT",
                    content = commentPlain,
                    keyword = keywordModeration,
                    gateway = aiGateway,
                    rules = moderationRuleRepo
                )
                if (moderation.blocked) {
                    val status = if (moderation.action == "AUTO_RATE_LIMIT") HttpStatusCode.TooManyRequests else HttpStatusCode.UnprocessableEntity
                    call.respond(status, ErrorResponse(moderation.message ?: "评论未通过安全检查"))
                    return@post
                }
                val comment = postRepo.addComment(postId, userId, req.content.trim(), req.replyToId)
                if (comment == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在或回复目标不存在"))
                else {
                    moderation.matches.mapNotNull { it.eventId }.takeIf { it.isNotEmpty() }?.let { ids ->
                        moderationRuleRepo.attachReference(ids, comment.id)
                    }
                    val postAuthorId = postRepo.getPostAuthorId(postId)
                    postAuthorId?.let { authorId ->
                        if (!userRepo.isBlockedEitherWay(authorId, userId)) {
                            // 1.130：评论附内容预览；1.132：附评论 id
                            pushService.enqueuePostInteraction(authorId, userId, postId, "COMMENT", comment.content, comment.id)
                        }
                    }
                    // 1.80：回复目标作者也通知（非发帖者本人时，避免重复）；1.122：互动类型细化 REPLY
                    val replyToId = req.replyToId
                    if (!replyToId.isNullOrBlank()) {
                        val replyAuthor = postRepo.getCommentAuthorId(replyToId)
                        if (replyAuthor != null && replyAuthor != postAuthorId && !userRepo.isBlockedEitherWay(replyAuthor, userId)) {
                            pushService.enqueuePostInteraction(replyAuthor, userId, postId, "REPLY", comment.content, comment.id)
                        }
                    }
                    call.respond(HttpStatusCode.Created, comment)
                }
            }

            put("/api/posts/{id}/comments/{cid}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfPostRestricted(userRepo, userId)) return@put
                if (!commentRateLimiter.acquire(userId, maxPerMinute = 30)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("评论操作过于频繁，请稍后再试"))
                    return@put
                }
                val postId = call.parameters["id"]!!
                val cid = call.parameters["cid"]!!
                val req = call.receiveBoundedText()?.let { parseJson<UpdateCommentRequest>(it) }
                if (req == null || !isValidCommentPayload(req.content)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("评论内容无效"))
                    return@put
                }
                val commentPlain = req.content.trim()
                val keywordModeration = moderationRuleRepo.evaluate(userId, "COMMENT", commentPlain)
                val moderation = ContentModerationService.combine(
                    userId = userId,
                    source = "COMMENT",
                    content = commentPlain,
                    keyword = keywordModeration,
                    gateway = aiGateway,
                    rules = moderationRuleRepo
                )
                if (moderation.blocked) {
                    val status = if (moderation.action == "AUTO_RATE_LIMIT") HttpStatusCode.TooManyRequests else HttpStatusCode.UnprocessableEntity
                    call.respond(status, ErrorResponse(moderation.message ?: "评论未通过安全检查"))
                    return@put
                }
                val comment = postRepo.updateCommentForUser(cid, postId, userId, req.content.trim())
                if (comment == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("评论不存在或无权编辑"))
                else {
                    moderation.matches.mapNotNull { it.eventId }.takeIf { it.isNotEmpty() }?.let { ids ->
                        moderationRuleRepo.attachReference(ids, comment.id)
                    }
                    call.respond(comment)
                }
            }

            delete("/api/posts/{id}/comments/{cid}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val postId = call.parameters["id"]!!
                val cid = call.parameters["cid"]!!
                val ok = postRepo.deleteCommentForUser(postId, cid, userId)
                if (ok) call.respond(
                buildJsonObject {
put("status", "deleted")
                }
            )
                else call.respond(HttpStatusCode.NotFound, ErrorResponse("评论不存在或无权删除"))
            }

            // 1.52：评论点赞/取消点赞（1.83：独立限流与动态点赞隔离）
            post("/api/posts/{id}/comments/{cid}/like") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                if (call.rejectIfPostRestricted(userRepo, userId)) return@post
                val postId = call.parameters["id"]!!
                val cid = call.parameters["cid"]!!
                if (!commentLikeRateLimiter.acquire(userId, maxPerMinute = 30)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                    return@post
                }
                val (likeCount, newLike) = postRepo.likeComment(postId, cid, userId)
                if (likeCount < 0) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("评论不存在"))
                    return@post
                }
                // 1.87：新点赞时通知评论作者（非本人、双向拉黑过滤）；1.113：互动类型细化 COMMENT_LIKE
                if (newLike) {
                    val commentAuthor = postRepo.getCommentAuthorId(cid)
                    if (commentAuthor != null && commentAuthor != userId && !userRepo.isBlockedEitherWay(commentAuthor, userId)) {
                        // 1.130：评论被赞附内容预览；1.132：附评论 id
                        val preview = postRepo.getComment(cid, userId)?.content
                        pushService.enqueuePostInteraction(commentAuthor, userId, postId, "COMMENT_LIKE", preview, cid)
                    }
                }
                call.respond(
                buildJsonObject {
put("status", "liked")
put("likeCount", likeCount)
                }
            )
            }
            delete("/api/posts/{id}/comments/{cid}/like") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val postId = call.parameters["id"]!!
                val cid = call.parameters["cid"]!!
                if (!commentLikeRateLimiter.acquire(userId, maxPerMinute = 30)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                    return@delete
                }
                val likeCount = postRepo.unlikeComment(postId, cid, userId)
                call.respond(
                buildJsonObject {
put("status", "unliked")
put("likeCount", likeCount)
                }
            )
            }

            // ─── 上传文件访问 API ────────────────
            // 必须经过 JWT 认证 — 旧 staticFiles("/uploads") 已被移除，避免 visibility 旁路
            // 头像：任何登录用户都可获取（头像本身是公开信息）
            get("/api/files/avatar/{filename}") {
                val filename = call.parameters["filename"]!!
                if (!filename.matches(Regex("^[A-Za-z0-9_.-]+$"))) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("文件名无效")); return@get }
                val avatarUrl = com.maodouchat.server.service.FileStorageService.avatarUrl(filename)
                if (avatarUrl == null || !userRepo.isCurrentAvatarUrl(avatarUrl)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("文件不存在"))
                    return@get
                }
                val file = com.maodouchat.server.service.FileStorageService.resolveFile("avatars", filename)
                if (file == null || !file.exists()) { call.respond(HttpStatusCode.NotFound, ErrorResponse("文件不存在")); return@get }
                call.respondFile(file)
            }
            get("/api/chats/{chatId}/avatar/file/{filename}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val chatId = call.parameters["chatId"]!!
                val filename = call.parameters["filename"]!!
                if (!filename.matches(Regex("^[A-Za-z0-9_.-]+$"))) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("文件名无效")); return@get }
                val chat = conversationQueryRepo.getById(chatId)
                if (chat == null || !conversationParticipantRepo.isParticipant(chatId, userId)) { call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问群头像")); return@get }
                if (com.maodouchat.server.service.FileStorageService.groupAvatarFilename(chat.groupAvatar, chatId) != filename) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("群头像不存在")); return@get
                }
                val file = com.maodouchat.server.service.FileStorageService.resolveFile("group-avatars", filename)
                if (file == null || !file.exists()) { call.respond(HttpStatusCode.NotFound, ErrorResponse("文件不存在")); return@get }
                call.respondFile(file)
            }
            // 动态图片：通过 filename→postId 映射查找对应动态，再校验可见性
            get("/api/files/post-image/{filename}") {
                val filename = call.parameters["filename"]!!
                if (!filename.matches(Regex("^[A-Za-z0-9_.-]+$"))) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("文件名无效")); return@get }
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val postId = postRepo.findPostIdByImageFilename(filename)
                if (postId == null) { call.respond(HttpStatusCode.NotFound, ErrorResponse("文件不存在")); return@get }
                if (!postRepo.canView(postId, userId)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("无权访问该动态图片"))
                    return@get
                }
                val file = com.maodouchat.server.service.FileStorageService.resolveFile("posts", filename)
                if (file == null || !file.exists()) { call.respond(HttpStatusCode.NotFound, ErrorResponse("文件不存在")); return@get }
                call.respondFile(file)
            }

        }
    }
}

@kotlinx.serialization.Serializable
internal data class PinnedMessagesUpdatedPayload(
    val chatId: String,
    val actorId: String,
    val pins: List<PinnedMessageResponse>
)

private data class ReceivedEncryptedAttachment(val byteCount: Long, val sha256: String)

private fun EncryptedAttachmentRecord.toUploadStatus(uploadedBytesOverride: Long? = null): AttachmentUploadStatusResponse {
    val actualBytes = uploadedBytesOverride ?: if (status == "UPLOADING") uploadedBytes else cipherSize
    return AttachmentUploadStatusResponse(
        id = id,
        cipherSha256 = cipherSha256,
        cipherSize = cipherSize,
        uploadedBytes = actualBytes.coerceIn(0L, cipherSize),
        status = status,
        expiresAt = expiresAt ?: 0L,
        complete = status == "UPLOADED" || status == "COMMITTED"
    )
}

private suspend fun reconcileAttachmentUpload(
    record: EncryptedAttachmentRecord,
    repository: EncryptedAttachmentRepository,
    userId: String
): EncryptedAttachmentRecord? {
    if (record.status != "UPLOADING") return record
    val actualBytes = withContext(Dispatchers.IO) { EncryptedAttachmentStorage.uploadedBytes(record.id) }
        ?.coerceAtMost(record.cipherSize) ?: 0L
    if (actualBytes < record.uploadedBytes) return null
    if (!repository.updateUploadProgress(record.id, userId, actualBytes)) return null
    if (actualBytes < record.cipherSize) return repository.get(record.id)?.copy(uploadedBytes = actualBytes)
    if (withContext(Dispatchers.IO) { EncryptedAttachmentStorage.sha256(record.id) } != record.cipherSha256) return null
    if (withContext(Dispatchers.IO) { EncryptedAttachmentStorage.finalizeResumableUpload(record.id) } == null) return null
    if (!repository.markUploaded(record.id, userId)) return null
    return repository.get(record.id)
}

// 9.151：支持 bytes=a-b / bytes=a- / bytes=-n 三种单区段形式（RFC 9110）。
// 非法或满足不了的单区段返回 null（→ 416）；多区段（含逗号）由调用方选择忽略回退全量。
private fun parseAttachmentRange(value: String, fileSize: Long): LongRange? {
    if (fileSize <= 0L) return null
    val trimmed = value.trim()
    Regex("^bytes=(\\d+)-(\\d*)$").matchEntire(trimmed)?.let { m ->
        val start = m.groupValues[1].toLongOrNull() ?: return null
        if (start >= fileSize) return null
        val endRaw = m.groupValues[2]
        val end = if (endRaw.isEmpty()) fileSize - 1 else (endRaw.toLongOrNull() ?: return null).coerceAtMost(fileSize - 1)
        return if (start <= end) start..end else null
    }
    Regex("^bytes=-(\\d+)$").matchEntire(trimmed)?.let { m ->
        val length = m.groupValues[1].toLongOrNull() ?: return null
        if (length <= 0L) return null
        return (fileSize - length).coerceAtLeast(0L)..(fileSize - 1)
    }
    return null
}

private suspend fun ApplicationCall.receiveEncryptedAttachmentChunk(maxBytes: Int): ByteArray? {
    val channel = receiveChannel()
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read < 0) break
        if (read == 0) {
            // 9.150：同上——等待数据/EOF，避免空转烧 CPU
            channel.awaitContent()
            continue
        }
        if (output.size() + read > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }

private suspend fun ApplicationCall.receiveEncryptedAttachment(
    target: java.io.File,
    maxBytes: Long
): ReceivedEncryptedAttachment? {
    val digest = MessageDigest.getInstance("SHA-256")
    val channel = receiveChannel()
    var total = 0L
    return try {
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) {
                    // 9.150：同上——慢速客户端逐字节上传时等待数据，避免空转烧 CPU
                    channel.awaitContent()
                    continue
                }
                total += read
                if (total > maxBytes) return null
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        if (total < 17L) null else ReceivedEncryptedAttachment(
            byteCount = total,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        )
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        target.delete()
        throw cancel
    } catch (_: Exception) {
        target.delete()
        null
    }
}

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
