package com.maodouchat.server.plugins

// WebSocket 装配母文件：configureSockets 安装入口；配置与入站处理已按域拆出（零行为改动）。
import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.getSuspendedUntil
import com.maodouchat.server.service.FcmPushService
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

fun Application.configureSockets(
    userRepo: UserRepository,
    signalingRepo: com.maodouchat.server.repository.SignalingRepository = com.maodouchat.server.repository.SignalingRepository(),
    pushService: FcmPushService = FcmPushService(
        com.maodouchat.server.repository.PushTokenRepository(),
        com.maodouchat.server.repository.NotificationPreferenceRepository()
    ),
    callInviteRateLimiter: CallInviteRateLimiter = CallInviteRateLimiter(),
    authTokenRepo: AuthTokenRepository = AuthTokenRepository(),
    turnCredentialService: com.maodouchat.server.service.TurnCredentialService? = null,
) {
    val participantRepository = ConversationParticipantRepository()
    val conversationQueryRepository = ConversationQueryRepository()
    val callSignalingService = com.maodouchat.server.service.CallSignalingService(
        signalingRepo,
        userRepo,
        conversationQueryRepository,
        callInviteRateLimiter,
        turnCredentialService,
    )
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
            // 不会把死 session 永久留在 ConnectionRegistry.onlineUsers / ConnectionRegistry.sessionAccessJtis / ConnectionRegistry.sessionAuthSessionIds。
            try {
                // 注册在线用户；绑定 access jti 供单设备 logout 精准踢线。
                // add 必须在 compute 锁内完成：computeIfAbsent{}.add 的 add 发生在 bin 锁外，
                // 与删除侧 compute 置 null 交错时，新 session 会挂到已脱离 map 的孤儿列表上。
                // 单用户并发连接数上限：防止持合法 JWT 的客户端开大量 WebSocket 耗尽
                // 内存/文件描述符（DoS）。广播放大已由 PRESENCE_FANOUT_CAP 限制。
                // 原子判读：把「计数检查 + 加入」放进 compute 内，避免并发 connect 时
                // check-then-act 竞态导致连接数短暂超过 MAX_WS_PER_USER（持合法 JWT 的客户端可借此放大连接）。
                var wsPerUserRejected = false
                ConnectionRegistry.onlineUsers.compute(userId) { _, sessions ->
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
                    ConnectionRegistry.sessionAccessJtis[this] = tokenId
                }
                ConnectionRegistry.sessionAuthSessionIds[this] = authSessionId
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
                // 状态锁内仅做 DB 上线标记；广播是挂起 I/O，移到锁外（见 PresenceService）。
                PresenceService.markOnline(userId, wsJson, userRepo)

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
                            val wsMsg = wsJson.decodeFromString<WsMessage>(text)
                            handleWsMessage(
                                wsMsg,
                                userId,
                                wsJson,
                                userRepo,
                                participantRepository,
                                conversationQueryRepository,
                                callSignalingService,
                                pushService,
                                wsMessageRateLimiter,
                                wsTypingRateLimiter,
                                wsSignalingRateLimiter,
                            )
                        } catch (e: CancellationException) {
                            // 协程取消必须重新抛出，否则结构化并发被破坏（外层 catch 也会吞掉）
                            throw e
                        } catch (e: Exception) {
                            sendSafe(this, wsJson.encodeToString(WsMessage("ERROR", wsJson.encodeToString(ErrorResponse("消息解析失败")))))
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
                ConnectionRegistry.sessionAccessJtis.remove(currentSession)
                ConnectionRegistry.sessionAuthSessionIds.remove(currentSession)
                ConnectionRegistry.removeSessionSendLock(currentSession)
                ConnectionRegistry.onlineUsers.compute(userId) { _, existing ->
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
                        PresenceService.markOffline(userId, wsJson, userRepo)
                    }
                }
            }
        }
    }
}
