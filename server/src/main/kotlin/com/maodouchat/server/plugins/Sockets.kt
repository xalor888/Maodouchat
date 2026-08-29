package com.maodouchat.server.plugins

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.CallInviteRateLimiter
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// 在线用户连接表: userId -> WebSocketSession
private const val MAX_FRAME_SIZE = 4L * 1024L * 1024L

/**
 * 单帧发送限时（9.226）。弱网/半死连接的 TCP 写缓冲可能长时间写不进去，
 * 没有限时会让一个坏 session 串行阻塞整条群扇出链；超时后按死连接清理。
 */
private const val WS_SEND_TIMEOUT_MS = 5_000L
/**
 * 在线状态广播的 fanout 上限。单个事件会向所有在线用户（除自己）逐一 sendToUser，
 * 代价 O(N)；N 个在线用户时若每个都触发广播，总代价 O(N²)。超过上限即跳过全量广播，
 * 由客户端定期轮询在线状态兜底，避免廉价的带宽/CPU 放大攻击面。
 */
private const val PRESENCE_FANOUT_CAP = 5000
/**
 * 单用户并发 WebSocket 连接数上限。PRESENCE_FANOUT_CAP 只限制广播的 fanout 代价，
 * 但连接数本身此前无上限——持合法 JWT 的客户端可开大量连接耗尽内存/文件描述符（DoS）。
 * 超过即拒绝新连接。
 */
private const val MAX_WS_PER_USER = 8
private val onlineUsers = ConcurrentHashMap<String, CopyOnWriteArrayList<WebSocketSession>>()

private val wsLogger = LoggerFactory.getLogger("Sockets")

/** Snapshot of user ids with at least one live WS session (admin ops / metrics). */
internal fun onlineUserIds(): List<String> = onlineUsers.keys.toList()

/** session -> access token jti；单设备 logout 只踢匹配 jti 的连接 */
private val sessionAccessJtis = ConcurrentHashMap<WebSocketSession, String>()
private val sessionAuthSessionIds = ConcurrentHashMap<WebSocketSession, String>()
/**
 * 单 session 发送互斥锁。Ktor 的 [WebSocketSession.send] 不保证并发安全：
 * 同一收件人的多条 fanout / 状态回执 / 打字指示可能来自不同发送者协程并发调用，
 * 若不加锁会导致帧交错损坏（半截帧、协议失配）。按 session 串行化发送。
 */
private class SessionSendLock(val mutex: Mutex = Mutex(), var users: Int = 0)
private val sessionSendLocks = ConcurrentHashMap<WebSocketSession, SessionSendLock>()

/** 仅在无发送者使用时删除锁，避免等待中的发送者与新建锁并发写同一 session。 */
private fun removeSessionSendLock(session: WebSocketSession) {
    sessionSendLocks.computeIfPresent(session) { _, current ->
        if (current.users == 0) null else current
    }
}

/**
 * 每用户在线状态迁移锁（分片）：把「containsKey 检查 + setOnline + 广播」串行化。
 * 否则旧连接 finally 的 check-then-act 与新连接的上线标记交错，会把刚重连的用户覆盖为离线。
 */
private val userStatusLocks = Array(64) { Mutex() }
private fun userStatusLock(userId: String): Mutex =
    userStatusLocks[(userId.hashCode() and Int.MAX_VALUE) % userStatusLocks.size]

/**
 * presence(上线/下线) 广播频控：防重连风暴放大。
 * 单用户反复 connect/disconnect 会每次触发 O(N) 全量广播；若无频控，1 个用户的重连速率会被
 * 放大为 N 倍出站消息（廉价 DoS）。限每用户 20 次/分钟——超过部分直接丢弃，由客户端定期轮询
 * 在线状态兜底（见 [PRESENCE_FANOUT_CAP] 注释），不影响最终状态收敛。
 */
private val presenceBroadcastRateLimiter = BoundedRateLimiter()

/** 9.136：动态删除广播频控——POST_DELETED 面向全体在线用户 fanout，
 * 普通用户可反复建/删动态触发 O(N) 放大；限每作者 30 次/分钟（管理/审核路径不限额）。
 * 丢弃超额广播不影响最终状态——客户端以 feed 刷新兜底收敛。 */
private val postDeleteBroadcastLimiter = BoundedRateLimiter()
fun Application.configureSockets(
    userRepo: UserRepository,
    signalingRepo: com.maodouchat.server.repository.SignalingRepository = com.maodouchat.server.repository.SignalingRepository(),
    pushService: FcmPushService = FcmPushService(
        com.maodouchat.server.repository.PushTokenRepository(),
        com.maodouchat.server.repository.NotificationPreferenceRepository()
    ),
    callInviteRateLimiter: CallInviteRateLimiter = CallInviteRateLimiter(),
    authTokenRepo: AuthTokenRepository = AuthTokenRepository()
) {
    val participantRepository = ConversationParticipantRepository()
    val conversationQueryRepository = ConversationQueryRepository()
    // Tests and standalone plugin installs may use the default FCM service instead of the
    // Routing-owned instance. shutdown() is idempotent when production shares one instance.
    environment.monitor.subscribe(ApplicationStopped) {
        pushService.shutdown()
    }
    // Server-side keepalive: periodic pings reap half-open TCP without relying on client
    // app-level pings; the timeout drops sessions that stop answering pings. Defaults are
    // conservative; tune via env if needed. Client-side reconnect policy (off-limits here):
    // exponential backoff with jitter on close codes 1001/1011, cap ~30s, honor GOING_AWAY.
    val pingPeriodSec = System.getenv("WS_PING_PERIOD_SECONDS")?.toLongOrNull()?.coerceIn(5, 300) ?: 15L
    val timeoutSec = System.getenv("WS_TIMEOUT_SECONDS")?.toLongOrNull()?.coerceIn(10, 600) ?: 30L
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(pingPeriodSec)
        timeout = Duration.ofSeconds(timeoutSec)
        maxFrameSize = MAX_FRAME_SIZE
        // RFC 6455：服务端→客户端帧 MUST NOT be masked；Ktor 此开关控制「出站帧」是否
        // mask，保持 false 才符合协议。客户端→服务端帧仍由 Ktor 按协议校验并解掩码。
        masking = false
    }

    val json = Json { ignoreUnknownKeys = true }

    // WebSocket 消息/打字频率限制：每用户每分钟上限，防止 DoS 和群 fanout 放大
    val wsMessageRateLimiter = BoundedRateLimiter()
    val wsTypingRateLimiter = BoundedRateLimiter()
    val wsSignalingRateLimiter = BoundedRateLimiter()

    routing {
        webSocket("/ws") {
            // JWT 只从 Authorization header 读取 — 绝不接受 query parameter（会泄漏到 access log / Referer）
            val token = call.request.headers[HttpHeaders.Authorization]
                ?.trim()
                ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substringAfter(' ')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (token == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "未认证"))
                return@webSocket
            }

            val decodedJwt = JwtConfig.verifyToken(token)
            if (decodedJwt == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "未认证"))
                return@webSocket
            }
            val userId = decodedJwt.subject
            if (userId.isNullOrBlank()) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "未认证"))
                return@webSocket
            }
            // 检查 token 版本和吊销状态 — 防止已登出/被吊销的 token 维持 WebSocket 连接
            val tokenVersion = JwtConfig.tokenVersion(decodedJwt)
            val tokenId = decodedJwt.id
            val authSessionId = JwtConfig.authSessionId(decodedJwt)
            if (!authTokenRepo.isAccessTokenAllowed(
                    userId,
                    tokenVersion,
                    tokenId,
                    authSessionId,
                    requireAuthSession = true
                )
            ) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "会话已失效"))
                return@webSocket
            }
            val suspendedUntil = userRepo.getSuspendedUntil(userId)
            if (suspendedUntil > 0L) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, wsRestrictionMessage(suspendedUntil, "账号已被临时封禁")))
                return@webSocket
            }
            // requireAuthSession=true 已保证 authSessionId 非空；这里显式判空兜底，
            // 不依赖跨文件隐式契约（曾用 !!，一旦契约变动会在注册后抛 NPE 泄漏 session）。
            if (authSessionId.isNullOrBlank()) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "会话已失效"))
                return@webSocket
            }
            var authWatchdog: Job? = null
            // 注册及其后的全部工作都在 try 内：任何异常（含 DB 复检抛错）都走 finally 幂等清理，
            // 不会把死 session 永久留在 onlineUsers / sessionAccessJtis / sessionAuthSessionIds。
            try {
                // 注册在线用户；绑定 access jti 供单设备 logout 精准踢线。
                // add 必须在 compute 锁内完成：computeIfAbsent{}.add 的 add 发生在 bin 锁外，
                // 与删除侧 compute 置 null 交错时，新 session 会挂到已脱离 map 的孤儿列表上。
                // 单用户并发连接数上限：防止持合法 JWT 的客户端开大量 WebSocket 耗尽
                // 内存/文件描述符（DoS）。广播放大已由 PRESENCE_FANOUT_CAP 限制。
                // 原子判读：把「计数检查 + 加入」放进 compute 内，避免并发 connect 时
                // check-then-act 竞态导致连接数短暂超过 MAX_WS_PER_USER（持合法 JWT 的客户端可借此放大连接）。
                var wsPerUserRejected = false
                onlineUsers.compute(userId) { _, sessions ->
                    val list = (sessions ?: CopyOnWriteArrayList())
                    if (list.size >= MAX_WS_PER_USER) {
                        wsPerUserRejected = true
                        sessions
                    } else {
                        list.add(this)
                        list
                    }
                }
                if (wsPerUserRejected) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "单用户连接数超限"))
                    return@webSocket
                }
                if (!tokenId.isNullOrBlank()) {
                    sessionAccessJtis[this] = tokenId
                }
                sessionAuthSessionIds[this] = authSessionId
                // 首检与注册之间存在吊销窗口：注册完成后立即复检一次（失败走 finally 清理）
                if (!authTokenRepo.isAccessTokenAllowed(
                        userId,
                        tokenVersion,
                        tokenId,
                        authSessionId,
                        requireAuthSession = true
                    )
                ) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "会话已失效"))
                    return@webSocket
                }
                // 状态锁内仅做 DB 上线标记；广播是挂起 I/O（逐个 sendToUser，O(N)），
                // 必须移到锁外，否则持锁期间阻塞该用户的状态迁移。
                userStatusLock(userId).withLock {
                    userRepo.setOnline(userId, true)
                }
                // 广播上线状态（锁外执行）
                broadcastUserStatus(userId, true, json, userRepo)

                // access token 过期/吊销后不能无限使用长连接；含 JWT exp + 空闲周期复检
                val accessExpiresAtMs = decodedJwt.expiresAt?.time ?: 0L
                fun sessionStillAllowed(): Boolean {
                    val now = System.currentTimeMillis()
                    if (accessExpiresAtMs > 0L && accessExpiresAtMs <= now) return false
                    if (!authTokenRepo.isAccessTokenAllowed(
                            userId,
                            tokenVersion,
                            tokenId,
                            authSessionId,
                            requireAuthSession = true
                        )
                    ) return false
                    if (userRepo.getById(userId) == null) return false
                    if (userRepo.getSuspendedUntil(userId) > 0L) return false
                    return true
                }
                // 空闲连接也必须复检：仅依赖入站帧会让只收推送的 socket 永不过期
                authWatchdog = launch {
                    while (isActive) {
                        delay(30_000L)
                        // 8.50 修复 M1：watchdog 循环体包 runCatching——DB 瞬时故障不得取消
                        // 整条 WS 连接（原无 catch，子协程异常取消父 Job → 全站重连风暴）
                        try {
                            if (!sessionStillAllowed()) {
                                val reason = when {
                                    accessExpiresAtMs > 0L && accessExpiresAtMs <= System.currentTimeMillis() -> "会话已过期"
                                    userRepo.getById(userId) == null -> "账号已注销"
                                    userRepo.getSuspendedUntil(userId) > 0L -> {
                                        wsRestrictionMessage(userRepo.getSuspendedUntil(userId), "账号已被临时封禁")
                                    }
                                    else -> "会话已失效"
                                }
                                // access token 到期是可恢复状态：客户端刷新后重连即可，不得视为永久登出。
                                // 1008 被客户端当作「永久鉴权死亡 → purge 加密库 + 强制登出」，
                                // 因此过期必须用可恢复关闭码（1013 TRY_AGAIN_LATER），吊销/封禁/踢线仍用 1008。
                                val isExpired = accessExpiresAtMs > 0L && accessExpiresAtMs <= System.currentTimeMillis()
                                val closeCode = if (isExpired) CloseReason.Codes.TRY_AGAIN_LATER
                                else CloseReason.Codes.VIOLATED_POLICY
                                close(CloseReason(closeCode, reason))
                                break
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            wsLogger.warn("WS auth watchdog transient error for user {}", userId, e)
                        }
                    }
                }
                var lastAuthRecheckAt = 0L
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val now = System.currentTimeMillis()
                        if (now - lastAuthRecheckAt >= 30_000L) {
                            lastAuthRecheckAt = now
                            if (!sessionStillAllowed()) {
                                val reason = when {
                                    accessExpiresAtMs > 0L && accessExpiresAtMs <= now -> "会话已过期"
                                    userRepo.getById(userId) == null -> "账号已注销"
                                    userRepo.getSuspendedUntil(userId) > 0L -> {
                                        wsRestrictionMessage(userRepo.getSuspendedUntil(userId), "账号已被临时封禁")
                                    }
                                    else -> "会话已失效"
                                }
                                // 与 watchdog 一致：access token 到期用可恢复码（客户端刷新重连），其余用 1008
                                val isExpired = accessExpiresAtMs > 0L && accessExpiresAtMs <= now
                                val closeCode = if (isExpired) CloseReason.Codes.TRY_AGAIN_LATER
                                else CloseReason.Codes.VIOLATED_POLICY
                                close(CloseReason(closeCode, reason))
                                break
                            }
                        }
                        val text = frame.readText()
                        try {
                            val wsMsg = json.decodeFromString<WsMessage>(text)
                            handleWsMessage(
                                wsMsg,
                                userId,
                                json,
                                userRepo,
                                participantRepository,
                                conversationQueryRepository,
                                signalingRepo,
                                pushService,
                                callInviteRateLimiter,
                                wsMessageRateLimiter,
                                wsTypingRateLimiter,
                                wsSignalingRateLimiter,
                            )
                        } catch (e: CancellationException) {
                            // 协程取消必须重新抛出，否则结构化并发被破坏（外层 catch 也会吞掉）
                            throw e
                        } catch (e: Exception) {
                            sendSafe(this, json.encodeToString(WsMessage("ERROR", json.encodeToString(ErrorResponse("消息解析失败")))))
                        }
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                // 连接正常关闭
            } catch (e: CancellationException) {
                // 协程取消：必须重新抛出以尊重结构化并发，不得吞掉
                throw e
            } catch (e: Exception) {
                // 接收循环中的任何异常都不能拖垮服务：记录并优雅关闭会话
                wsLogger.warn("WebSocket receive loop error for user {}: {}", userId, e.message, e)
                try {
                    close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "internal error"))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // ignore close failures during error handling
                }
            } finally {
                authWatchdog?.cancel()
                // 原子清理：compute 内部不能调挂起函数，改用 boolean holder
                val cleared = booleanArrayOf(false)
                val currentSession = this
                sessionAccessJtis.remove(currentSession)
                sessionAuthSessionIds.remove(currentSession)
                removeSessionSendLock(currentSession)
                onlineUsers.compute(userId) { _, existing ->
                    val list = if (existing != null) existing else CopyOnWriteArrayList<WebSocketSession>()
                    list.remove(currentSession)
                    if (list.isEmpty()) {
                        cleared[0] = true
                        null
                    } else list
                }
                if (cleared[0]) {
                    // 协程被取消（停机/engine 取消）时 finally 里的挂起调用会立刻抛
                    // CancellationException，离线标记与广播必须在 NonCancellable 下完成。
                    withContext(NonCancellable) {
                        // 状态锁内仅做 DB 离线标记；广播是挂起 I/O，移到锁外。
                        userStatusLock(userId).withLock {
                            if (!onlineUsers.containsKey(userId)) {
                                userRepo.setOnline(userId, false)
                            }
                        }
                        // 广播离线：锁外执行；释放锁后再次确认仍无会话，
                        // 避免新连接在锁释放后加入导致 online→offline 闪烁。
                        if (!onlineUsers.containsKey(userId)) {
                            broadcastUserStatus(userId, false, json, userRepo)
                        }
                    }
                }
            }
        }
    }
}

private suspend fun WebSocketSession.sendError(
    message: String,
    json: Json,
    code: String? = null,
    retryAfterSeconds: Long? = null,
    messageId: String? = null
) {
    sendSafe(
        this,
        json.encodeToString(
            WsMessage("ERROR", json.encodeToString(ErrorResponse(message, code, retryAfterSeconds, messageId)))
        )
    )
}

private fun wsRestrictionMessage(until: Long, action: String): String {
    val remainingMinutes = ((until - System.currentTimeMillis()).coerceAtLeast(0) + 59_999L) / 60_000L
    return "$action，约 ${remainingMinutes.coerceAtLeast(1)} 分钟后恢复"
}

private suspend fun WebSocketSession.handleWsMessage(
    wsMsg: WsMessage,
    senderId: String,
    json: Json,
    userRepo: UserRepository,
    participantRepository: ConversationParticipantRepository,
    conversationQueryRepository: ConversationQueryRepository,
    signalingRepo: com.maodouchat.server.repository.SignalingRepository,
    pushService: FcmPushService,
    callInviteRateLimiter: CallInviteRateLimiter,
    wsMessageRateLimiter: BoundedRateLimiter,
    wsTypingRateLimiter: BoundedRateLimiter,
    wsSignalingRateLimiter: BoundedRateLimiter
) {
    when (wsMsg.type) {
        // 9.3xx（Ideaura 式应用层心跳）：客户端每 20s 发 PING，服务端回 PONG。
        // 协议层 WebSocket ping 经部分 NAT/代理会被吞掉，应用层心跳让客户端能确定性
        // 检测死连接并快速重连（保证推送不静默中断）。PONG 负载回显客户端时间戳。
        "PING" -> {
            val payload = runCatching { wsMsg.payload }.getOrDefault("")
            sendSafe(this, json.encodeToString(WsMessage("PONG", payload)))
        }
        "PRESENCE" -> {
            val foreground = wsMsg.payload.equals("true", ignoreCase = true) ||
                wsMsg.payload.contains("\"foreground\":true", ignoreCase = true)
            userStatusLock(senderId).withLock {
                userRepo.setOnline(senderId, foreground)
            }
            broadcastUserStatus(senderId, foreground, json, userRepo)
        }
        "TYPING" -> {
                // 打字指示：每用户每分钟 120 次。30/min 会被正常连打 + 3s debounce 打满，
                // 表现为「正在输入」丢失；丢弃即可，不回 ERROR（避免客户端 toast 频繁）。
                if (!wsTypingRateLimiter.acquire(senderId, maxPerMinute = 120)) return
                val payload = json.decodeFromString<TypingPayload>(wsMsg.payload)
                if (!participantRepository.isParticipant(payload.chatId, senderId)) return
                // 双向拉黑过滤：被对方拉黑或拉黑对方都不再收到 typing 侧信道
                val participants = participantRepository.participantIds(payload.chatId)
                val blockedIds = userRepo.blockedEitherWayIdsInTx(senderId, participants)
                participants.filter { it != senderId && it !in blockedIds }
                    .forEach { sendToUser(it, json.encodeToString(WsMessage("USER_TYPING", json.encodeToString(TypingPayload(senderId, payload.chatId, payload.isTyping))))) }
            }

            "SIGNALING" -> {
            // Signaling is persisted before real-time fanout and disabled during maintenance.
            if (RuntimeConfigService.isMaintenanceMode()) {
                sendError(
                    RuntimeConfigService.get(RuntimeConfigService.KEY_MAINTENANCE_MESSAGE)
                        .ifBlank { "System under maintenance" },
                    json
                )
                return
            }
            // WebRTC 信令：只允许转发给真实用户，避免无效目标和自发自收
            val payload = json.decodeFromString<OutgoingSignalingPayload>(wsMsg.payload)
            if (!isValidSignalPayload(payload.type, payload.payload) || !isValidCallId(payload.callId)) {
                sendError("信令内容无效", json)
                return
            }
            if (payload.toUserId == senderId) {
                sendError("不能向自己发送信令", json)
                return
            }
            val restriction = userRepo.getMessageRestrictionUntil(senderId).takeIf { it > 0L }
                ?: userRepo.getSuspendedUntil(senderId).takeIf { it > 0L }
            if (restriction != null) {
                sendError(wsRestrictionMessage(restriction, "你已被限制发起通话"), json)
                return
            }
            if (userRepo.getById(payload.toUserId) == null) {
                sendError("信令目标用户不存在", json)
                return
            }
            if (!isValidGroupSignalMetadata(payload.groupId, payload.groupMemberIds, payload.groupInvite, payload.callId, senderId, payload.toUserId, conversationQueryRepository)) {
                sendError("群通话元数据无效", json)
                return
            }
            if (userRepo.isBlockedEitherWay(senderId, payload.toUserId)) {
                sendError("无法与已屏蔽的用户发起通话", json)
                return
            }
            if (payload.groupId.isBlank() && !conversationQueryRepository.shareConversation(senderId, payload.toUserId)) {
                sendError("双方无共同会话，无法发起通话", json)
                return
            }
            if (CallInviteRateLimiter.isInitialInvite(payload.type, payload.groupId, payload.groupInvite)) {
                val key = CallInviteRateLimiter.sessionKey(payload.callId, payload.groupId, payload.toUserId)
                val decision = callInviteRateLimiter.tryAcquire(senderId, key)
                if (!decision.allowed) {
                    sendError(
                        "发起通话过于频繁，请稍后重试",
                        json,
                        code = "CALL_INVITE_RATE_LIMITED",
                        retryAfterSeconds = decision.retryAfterSeconds
                    )
                    return
                }
            } else if (!wsSignalingRateLimiter.acquire(senderId, maxPerMinute = 120)) {
                // 初次 offer 走 CallInviteRateLimiter；后续 ice-candidate/answer 等信令每用户限流，
                // 防止持合法 JWT 的客户端高频信令对目标用户做存储 + fanout 放大（8.35 修复）
                sendError("信令发送过于频繁，请稍后重试", json)
                return
            }
            // 先持久化再实时推送：弱网断连时对端仍可通过 REST 轮询获取信令
            // 终端信令与清理同事务，避免 store 已提交、clear 未执行的窗口
            val terminalTypes = setOf("hang-up", "busy", "reject")
            if (payload.type.lowercase() in terminalTypes) {
                signalingRepo.storeTerminalAndClearOthers(
                    senderId, payload.toUserId, payload.type, payload.payload,
                    payload.callId, payload.groupId, payload.groupMemberIds, payload.groupInvite
                )
            } else {
                signalingRepo.store(
                    senderId, payload.toUserId, payload.type, payload.payload,
                    payload.callId, payload.groupId, payload.groupMemberIds, payload.groupInvite
                )
            }
            val signalMsg = json.encodeToString(WsMessage("SIGNALING", json.encodeToString(
                IncomingSignalingPayload(senderId, payload.type, payload.payload, payload.callId, payload.groupId, payload.groupMemberIds, payload.groupInvite)
            )))
            sendToUser(payload.toUserId, signalMsg)
            if (payload.type.equals("offer", ignoreCase = true) && (payload.groupId.isBlank() || payload.groupInvite)) {
                pushService.enqueueIncomingCall(
                    recipientId = payload.toUserId,
                    senderId = senderId,
                    isVideo = sdpHasActiveVideo(payload.payload),
                    callId = payload.callId
                )
            }
        }

        // 未知消息类型：绝不静默吞掉，记录并回错误，避免客户端/服务端协议失配时无感知
        else -> {
            wsLogger.warn("WebSocket received unknown message type: {}", wsMsg.type)
            sendError("不支持的 WebSocket 命令", json, code = "UNSUPPORTED_WS_COMMAND")
        }
    }
}

private suspend fun broadcastUserStatus(userId: String, isOnline: Boolean, json: Json, userRepo: UserRepository) {
    // 隐私保护：nobody / showOnline=false 不广播在线状态
    if (!userRepo.shouldBroadcastOnline(userId)) return
    // 频控：防重连风暴放大（单用户 connect/disconnect 反复触发 O(N) 全量广播）。
    // 丢弃超额广播不影响最终状态——客户端以定期轮询在线状态兜底收敛。
    if (!presenceBroadcastRateLimiter.acquire(userId, maxPerMinute = 20)) return
    // 防广播风暴：在线用户过多时跳过全量 fanout（O(N) 每条事件 → O(N²) 放大攻击面），
    // 由客户端定期轮询在线状态兜底。
    if (onlineUsers.size > PRESENCE_FANOUT_CAP) return
    val status = UserStatusPayload(userId, isOnline, System.currentTimeMillis())
    val msg = json.encodeToString(WsMessage("USER_STATUS", json.encodeToString(status)))
    // 广播给所有在线用户（除自己）；双向拉黑不泄露在线状态
    // 8.48 修复 H7：一次批量查 viewer 与全体在线用户的双向拉黑（此前逐在线用户
    // isBlockedEitherWay → 每人 2 次 BlockedUsers 查询，PRESENCE_FANOUT_CAP=500 → 1000 次）
    val onlineIds = onlineUsers.keys.filter { it != userId }
    val blockedIds = try { userRepo.blockedEitherWayIdsInTx(userId, onlineIds) } catch (_: Exception) { emptySet() }
    onlineIds.forEach { uid ->
        if (uid !in blockedIds && userRepo.shouldShowOnlineTo(userId, uid)) {
            sendToUser(uid, msg)
        }
    }
}

@kotlinx.serialization.Serializable
private data class PostDeletedPayload(val postId: String)

/** 动态被作者/版主删除后向所有在线客户端广播，前端即时移除，避免残留。 */
internal suspend fun broadcastPostDeleted(postId: String, actorId: String? = null) {
    if (postId.isBlank()) return
    // 9.136：与 presence 广播同构的防护——频控（普通用户路径）+ 在线规模上限，
    // 防反复建/删动态把单事件放大为 O(N) 全量 fanout
    if (actorId != null && !postDeleteBroadcastLimiter.acquire(actorId, maxPerMinute = 30)) return
    if (onlineUsers.size > PRESENCE_FANOUT_CAP) return
    val json = Json { ignoreUnknownKeys = true }
    val message = json.encodeToString(
        WsMessage.serializer(),
        WsMessage("POST_DELETED", json.encodeToString(PostDeletedPayload.serializer(), PostDeletedPayload(postId)))
    )
    onlineUserIds().forEach { sendToUser(it, message) }
}

internal suspend fun broadcastUserVisibilityRevoked(
    userId: String,
    onlineRevoked: Boolean,
    statusRevoked: Boolean,
    json: Json,
    userRepo: UserRepository
) {
    if (!onlineRevoked && !statusRevoked) return
    val payload = UserStatusPayload(
        userId = userId,
        isOnline = false,
        lastSeen = 0L,
        onlineRevoked = onlineRevoked,
        statusRevoked = statusRevoked
    )
    val message = json.encodeToString(WsMessage("USER_STATUS", json.encodeToString(payload)))
    // 本人其他设备也需清缓存；双向拉黑关系不得借撤回事件感知对方活动。
    // 8.48 修复 H7（同构）：批量查双向拉黑（此前逐在线用户 isBlockedEitherWay → 2 次查询/人）
    val onlineIds = onlineUsers.keys.filter { it != userId }
    val blockedIds = try { userRepo.blockedEitherWayIdsInTx(userId, onlineIds) } catch (_: Exception) { emptySet() }
    onlineUsers.keys
        .filter { viewerId -> viewerId == userId || viewerId !in blockedIds }
        .forEach { viewerId -> sendToUser(viewerId, message) }
}

/**
 * 并发安全地向单个 session 发送文本帧。Ktor 的 [WebSocketSession.send] 不保证并发安全，
 * 用 [sessionSendLocks] 中按 session 的互斥锁串行化，避免同收件人的多路 fanout 帧交错。
 */
internal suspend fun runWithWsSendTimeout(
    timeoutMs: Long = WS_SEND_TIMEOUT_MS,
    send: suspend () -> Unit,
) {
    try {
        kotlinx.coroutines.withTimeout(timeoutMs) {
            send()
        }
    } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
        // A timeout owned by this helper leaves the parent active; cancellation from the
        // WebSocket/application parent must retain CancellationException semantics so its Job
        // is not accidentally converted into a dead-session IOException.
        if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw error
        throw java.io.IOException("ws send timeout after ${timeoutMs}ms", error)
    }
}

private suspend fun sendSafe(session: WebSocketSession, text: String) {
    val lock = sessionSendLocks.compute(session) { _, existing ->
        val current = existing ?: SessionSendLock()
        current.users++
        current
    }!!
    try {
        lock.mutex.withLock {
            // 9.226：发送限时——卡住的会话（TCP 写缓冲满/弱网）不得拖住整条群扇出链；
            // 超时异常转换为 IOException，让调用方按死连接清理（它不是 CancellationException
            // 子类，本可直穿 catch，但显式转换表明超时语义，且避免将来实现变更时误判）。
            runWithWsSendTimeout {
                session.send(Frame.Text(text))
            }
        }
    } finally {
        sessionSendLocks.computeIfPresent(session) { _, current ->
            if (current === lock) {
                if (current.users > 1) {
                    current.users--
                    current
                } else {
                    null
                }
            } else {
                current
            }
        }
    }
}

internal suspend fun sendToUser(userId: String, message: String) {
    val sessions = onlineUsers[userId] ?: return
    val failedSessions = mutableListOf<WebSocketSession>()
    sessions.forEach { session ->
        try {
            sendSafe(session, message)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failedSessions.add(session)
        }
    }
    failedSessions.forEach { session ->
        try {
            session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "send failed"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        sessionAccessJtis.remove(session)
        sessionAuthSessionIds.remove(session)
        removeSessionSendLock(session)
        sessions.remove(session)
    }
    // 用 compute 原子地检查并移除空列表，避免 check-then-remove 竞态：
    // 旧实现先 isEmpty() 再 remove()，两步之间新 session 可能被加入列表却被误删。
    onlineUsers.compute(userId) { _, existing ->
        if (existing === sessions && sessions.isEmpty()) null else existing
    }
}

/**
 * Force-close all live WebSocket sessions for [userId].
 * Call after password change / logout-all / account deactivation so revoked tokens
 * cannot keep accepting signaling until the TCP socket drops.
 */
internal suspend fun disconnectUserSessions(userId: String, reason: String = "会话已失效") {
    if (userId.isBlank()) return
    val sessions = onlineUsers.remove(userId) ?: return
    sessions.forEach { session ->
        sessionAccessJtis.remove(session)
        sessionAuthSessionIds.remove(session)
        removeSessionSendLock(session)
        try {
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason.take(120)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    markOfflineAndBroadcastIfNoSessions(userId)
}

internal suspend fun disconnectUserSessionsByAuthSessionIds(
    userId: String,
    authSessionIds: Set<String>,
    reason: String = "会话已失效"
) {
    if (userId.isBlank() || authSessionIds.isEmpty()) return
    val sessions = onlineUsers[userId] ?: return
    val toClose = sessions.filter { session -> sessionAuthSessionIds[session] in authSessionIds }
    toClose.forEach { session ->
        sessionAccessJtis.remove(session)
        sessionAuthSessionIds.remove(session)
        removeSessionSendLock(session)
        try {
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason.take(120)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        sessions.remove(session)
    }
    onlineUsers.compute(userId) { _, existing ->
        if (existing === sessions && sessions.isEmpty()) null else existing
    }
    markOfflineAndBroadcastIfNoSessions(userId)
}

/**
 * 单设备 logout：只关闭 access jti 匹配的 WS，避免误踢其他仍有效的设备。
 * 若无 jti（body-only logout 未带 Authorization），则只吊销 refresh token，不踢任何 WS
 * （无法区分会话时宁可保留连接，避免误踢其他仍有效的设备；access token 自然过期后 WS 会被 authWatchdog 踢掉）。
 */
internal suspend fun disconnectUserSessionsByAccessJti(
    userId: String,
    accessJti: String?,
    reason: String = "已退出登录"
) {
    if (userId.isBlank()) return
    // 无 jti（过期 access / body-only logout）：只吊销 refresh，不误踢其他设备 WS
    if (accessJti.isNullOrBlank()) {
        return
    }
    val sessions = onlineUsers[userId] ?: return
    val toClose = sessions.filter { session -> sessionAccessJtis[session] == accessJti }
    toClose.forEach { session ->
        sessionAccessJtis.remove(session)
        sessionAuthSessionIds.remove(session)
        removeSessionSendLock(session)
        try {
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason.take(120)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        sessions.remove(session)
    }
    onlineUsers.compute(userId) { _, existing ->
        if (existing === sessions && sessions.isEmpty()) null else existing
    }
    markOfflineAndBroadcastIfNoSessions(userId)
}

/**
 * 强制断连后若该用户已无任何在线会话，落库离线并向其他在线用户广播。
 * 状态锁 + 锁外二次确认避免新连接注册后被旧清理路径误标离线。
 */
private suspend fun markOfflineAndBroadcastIfNoSessions(userId: String) {
    userStatusLock(userId).withLock {
        if (!onlineUsers.containsKey(userId)) {
            runCatching {
                UserRepository().setOnline(userId, false)
            }
        }
    }
    if (!onlineUsers.containsKey(userId)) {
        broadcastUserStatus(userId, false, Json { ignoreUnknownKeys = true }, UserRepository())
    }
}

@kotlinx.serialization.Serializable
private data class UserStatusPayload(
    val userId: String,
    val isOnline: Boolean,
    val lastSeen: Long = 0,
    val onlineRevoked: Boolean = false,
    val statusRevoked: Boolean = false
)

@kotlinx.serialization.Serializable
private data class OutgoingSignalingPayload(
    val toUserId: String,
    val type: String,
    val payload: String,
    val callId: String = "",
    val groupId: String = "",
    val groupMemberIds: List<String> = emptyList(),
    val groupInvite: Boolean = false
)

@kotlinx.serialization.Serializable
private data class IncomingSignalingPayload(
    val fromUserId: String,
    val type: String,
    val payload: String,
    val callId: String = "",
    val groupId: String = "",
    val groupMemberIds: List<String> = emptyList(),
    val groupInvite: Boolean = false
)

@kotlinx.serialization.Serializable
internal data class TypingPayload(val userId: String, val chatId: String, val isTyping: Boolean)
