package com.maodouchat.network

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageReaction
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

sealed class WebSocketEvent {
    data class MessageReceived(val message: Message) : WebSocketEvent()
    data class StatusChanged(val messageId: String, val status: MessageStatus) : WebSocketEvent()
    /** 跨设备已读同步：同账号其他设备标记了该会话已读。 */
    data class ChatMarkedRead(val chatId: String) : WebSocketEvent()
    data class MessageDeleted(val messageId: String, val chatId: String) : WebSocketEvent()
    data class MessageRevoked(val messageId: String, val chatId: String) : WebSocketEvent()
    data class MessageEdited(val messageId: String, val chatId: String, val content: String, val editedAt: Long? = null) : WebSocketEvent()
    data class MessageReactionUpdated(val chatId: String, val messageId: String, val userId: String, val reactions: List<MessageReaction>) : WebSocketEvent()
    data class PinnedMessagesUpdated(
        val chatId: String,
        val actorId: String,
        val pins: List<PinnedMessageDto>
    ) : WebSocketEvent()
    data class DisappearingMessagesUpdated(
        val chatId: String,
        val seconds: Int,
        val updatedAt: Long = 0L
    ) : WebSocketEvent()
    data class MessageExpires(
        val messageId: String,
        val chatId: String,
        val expiresAt: Long
    ) : WebSocketEvent()
    data class UserOnline(
        val userId: String,
        val isOnline: Boolean,
        val lastSeen: Long = 0,
        val onlineRevoked: Boolean = false,
        val statusRevoked: Boolean = false
    ) : WebSocketEvent()
    data class AdminBroadcast(val title: String, val text: String, val ts: Long = 0L) : WebSocketEvent()
    data class SignalingReceived(
        val fromUserId: String,
        val type: String,
        val payload: String,
        val callId: String = "",
        val groupId: String = "",
        val groupMemberIds: List<String> = emptyList(),
        val groupInvite: Boolean = false
    ) : WebSocketEvent()
    data class ServerError(
        val code: String? = null,
        val retryAfterSeconds: Long? = null,
        val message: String = ""
    ) : WebSocketEvent()
    data class UserTyping(val userId: String, val chatId: String, val isTyping: Boolean) : WebSocketEvent()
    data class GroupRevisionChanged(
        val chatId: String,
        val memberRevision: Long,
        val reason: String,
        val actorId: String? = null,
        val targetUserId: String? = null
    ) : WebSocketEvent()
    data class FriendRequestUpdated(
        val action: String,
        val request: FriendRequestDto
    ) : WebSocketEvent()
    data class Connected(val success: Boolean) : WebSocketEvent()
    data class Error(
        val kind: WebSocketErrorKind,
        val debugDetail: String? = null
    ) : WebSocketEvent()
    data object Disconnected : WebSocketEvent()
}

internal data class ResolvedUserVisibility(
    val isOnline: Boolean,
    val status: String,
    val lastSeen: Long
)

internal fun resolveUserVisibility(
    currentIsOnline: Boolean,
    currentStatus: String,
    currentLastSeen: Long,
    eventIsOnline: Boolean,
    eventLastSeen: Long,
    onlineRevoked: Boolean,
    statusRevoked: Boolean
): ResolvedUserVisibility = ResolvedUserVisibility(
    isOnline = when {
        onlineRevoked -> false
        statusRevoked -> currentIsOnline
        else -> eventIsOnline
    },
    status = if (statusRevoked) "" else currentStatus,
    lastSeen = when {
        onlineRevoked -> 0L
        statusRevoked -> currentLastSeen
        else -> eventLastSeen
    }
)

enum class WebSocketErrorKind {
    CONNECTION,
    ENVELOPE_PARSE,
    MESSAGE_PARSE,
    STATUS_PARSE,
    DELETE_PARSE,
    EDIT_PARSE,
    REACTION_PARSE,
    PIN_PARSE,
    DISAPPEARING_PARSE,
    EXPIRES_PARSE,
    USER_STATUS_PARSE,
    TYPING_PARSE,
    GROUP_REVISION_PARSE,
    SIGNALING_PARSE,
    FRIEND_REQUEST_PARSE
}

@Serializable
internal data class WsMessage(val type: String, val payload: String)

@Serializable
private data class SendPayload(val id: String, val chatId: String, val content: String, val type: String, val sealedSender: Boolean = false, val silent: Boolean = false,  val sealedSenderCertificate: String? = null)

@Serializable
private data class StatusPayload(val messageId: String, val status: String)

@Serializable
private data class TypingPayload(val userId: String, val chatId: String, val isTyping: Boolean)

@Serializable
private data class NudgePayload(val chatId: String, val targetName: String)

@Serializable
private data class AdminBroadcastPayload(
    val title: String = "System",
    val text: String = "",
    val actorId: String = "",
    val ts: Long = 0L
)

@Serializable
private data class IncomingMessage(
    val id: String,
    val chatId: String,
    val senderId: String,
    val content: String,
    // 8.52 契约修复 CRITICAL：Server 端 Json encodeDefaults=false，type=="TEXT"（默认值）时
    // 键被省略；此前无默认值 → 每条 TEXT 消息 WS 解码抛 MissingFieldException 被丢弃。
    val type: String = "TEXT",
    val timestamp: Long,
    val editedAt: Long? = null,
    val starred: Boolean = false,
    val reactions: List<MessageReaction> = emptyList(),
    val expiresAt: Long? = null,
    val sealedSender: Boolean = false
)

@Serializable
private data class IncomingStatus(val messageId: String, val status: String)

@Serializable
private data class IncomingChatMarkedRead(val chatId: String)

@Serializable
private data class IncomingUserStatus(
    val userId: String,
    val isOnline: Boolean,
    val lastSeen: Long = 0,
    val onlineRevoked: Boolean = false,
    val statusRevoked: Boolean = false
)

@Serializable
private data class IncomingSignaling(
    val fromUserId: String,
    val type: String,
    val payload: String,
    val callId: String = "",
    val groupId: String = "",
    val groupMemberIds: List<String> = emptyList(),
    val groupInvite: Boolean = false
)

@Serializable
private data class IncomingServerError(
    val error: String = "",
    val code: String? = null,
    val retryAfterSeconds: Long? = null
)

@Serializable
private data class IncomingMessageDeleted(val messageId: String, val chatId: String)

@Serializable
private data class IncomingMessageEdited(
    val messageId: String,
    val chatId: String = "",
    val content: String,
    val editedAt: Long? = null
)

@Serializable
private data class IncomingMessageReactionUpdated(
    val chatId: String,
    val messageId: String,
    val userId: String,
    val reactions: List<MessageReaction> = emptyList()
)

@Serializable
    private data class IncomingPinnedMessagesUpdated(
        val chatId: String,
        val actorId: String = "",
        val pins: List<PinnedMessageDto> = emptyList()
    )

    @Serializable
    private data class IncomingDisappearingMessagesUpdated(
        val chatId: String,
        val seconds: Int = 0,
        val updatedAt: Long = 0
    )

    @Serializable
    private data class IncomingMessageExpires(
        val messageId: String,
        val chatId: String,
        val expiresAt: Long
    )

@Serializable
private data class IncomingTyping(val userId: String, val chatId: String, val isTyping: Boolean)

@Serializable
private data class IncomingFriendRequestEvent(
    val action: String,
    val request: FriendRequestDto
)

@Serializable
private data class IncomingGroupRevisionChanged(
    val chatId: String,
    val memberRevision: Long,
    val reason: String,
    val actorId: String? = null,
    val targetUserId: String? = null
)

/**
 * WebSocket 客户端（单例 + 自动重连）
 */
object WebSocketClient {

    private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
    private const val MAX_RECONNECT_DELAY_MS = 30_000L
    // 最大重连次数上限，避免 SSL 证书错误、DNS 持续失败等不可恢复场景无限重试耗电
    private const val MAX_RECONNECT_ATTEMPTS = 20

    private val json = Json { ignoreUnknownKeys = true }
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    // 业务事件不能向新订阅者重放，否则新页面可能重复处理旧消息、删除或群变更。
    // 来电 offer 由 IncomingCallCoordinator 和服务端 pending signaling 持久承接。
    // 事件桥接协程挂在独立作用域：不随连接生命周期 reset，避免重连期间队列积压被取消。
    private val eventScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val eventBus = NonReplayingEventBus<WebSocketEvent>(capacity = 64, scope = eventScope)
    val events: SharedFlow<WebSocketEvent> = eventBus.flow
    private val sessionGate = WebSocketSessionGate()

    @Volatile
    private var isConnected = false
    private val connecting = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var shouldReconnect = false

    private var reconnectJob: Job? = null
    // AtomicLong 保证 reconnectDelayMs 在 OkHttp 回调线程和重连协程之间可见且原子
    private val reconnectDelayMs = AtomicLong(INITIAL_RECONNECT_DELAY_MS)
    // 重连尝试计数器，达到 MAX_RECONNECT_ATTEMPTS 后停止重连，等用户手动操作（如重新登录、检查网络）
    private val reconnectAttempts = java.util.concurrent.atomic.AtomicInteger(0)

    // AtomicReference 保证在多线程重连和主线程 connect() 之间读到的 URL/token 对是完整的
    private val serverUrl = AtomicReference<String?>(null)
    private val authToken = AtomicReference<String?>(null)

    // 带 parent Job，使 disconnect() 能取消所有重连子协程
    // cancel 后必须通过 resetConnectionScope() 重建；Job 被 cancel 后不可复用
    @Volatile
    private var connectionParent: Job = SupervisorJob()
    private val scope get() = CoroutineScope(connectionParent + Dispatchers.IO)

    private fun resetConnectionScope() {
        if (connectionParent.isActive) connectionParent.cancel()
        connectionParent = SupervisorJob()
    }

    @Synchronized
    fun connect(newServerUrl: String, newToken: String, isReconnect: Boolean = false, reconnectSession: Long? = null) {
        // 8.33 修复（登出↔重连竞态）：重连 job 在 delay 后可能晚于 disconnect() 执行——
        // disconnect() 先置 shouldReconnect=false，此时任何自动重连都必须放弃，否则
        // 会用旧 token 复活一个「当前会话」（旧账号持续在线直至 1008，事件污染新账号）。
        // 手动连接（登录/换号）isReconnect=false 不受此守卫影响。
        if (isReconnect && !shouldReconnect) return
        // 8.33 修复：重连 job 捕获其失败时的会话代号；若期间发生过 disconnect/换号/刷新
        // （nextSession 递增代号），该 job 已过期，即使 shouldReconnect 再次为 true 也拒绝，
        // 杜绝「旧 job 在用户重新登录后仍用旧 token 拆掉新连接」的窗口。
        if (isReconnect && reconnectSession != null && !sessionGate.isCurrent(reconnectSession)) return
        // 8.33 修复：空白 token 一律拒绝（登出清理顺序为 shouldReconnect→parent cancel→token 清空，
        // 此守卫兜底任何晚到路径，杜绝「Bearer null」僵尸连接）。
        if (newToken.isBlank()) return
        // 上一次 disconnect() 已 cancel 旧 parent Job，重置以便重连子协程可以重新启动
        if (!connectionParent.isActive) resetConnectionScope()
        val oldUrl = serverUrl.get()
        val oldToken = authToken.get()
        if (isConnected && oldUrl == newServerUrl && oldToken == newToken) return
        val parametersChanged = oldUrl != newServerUrl || oldToken != newToken
        // 同参数连接正在建立时复用；换号/换 token 必须立即淘汰旧连接，不能被旧 CAS 挡住。
        if (connecting.get() && !parametersChanged) return
        val session = sessionGate.nextSession()
        connecting.set(true)
        if (parametersChanged) {
            reconnectJob?.cancel()
            webSocket?.close(1000, "connection parameters changed")
            webSocket = null
            isConnected = false
        }

        shouldReconnect = true
        // 手动连接（登录/换号/换 token）重置重连计数器和退避延迟，避免继承旧连接的重连预算；
        // 自动重连路径（scheduleReconnect）传 isReconnect=true 不重置，否则每次重连都会把计数器
        // 清零，永远达不到 MAX_RECONNECT_ATTEMPTS，导致可恢复错误（如 DNS 抖动）无限重连耗电。
        if (!isReconnect) {
            reconnectAttempts.set(0)
            reconnectDelayMs.set(INITIAL_RECONNECT_DELAY_MS)
        }
        serverUrl.set(newServerUrl)
        authToken.set(newToken)

        val request = Request.Builder()
            .url(newServerUrl)
            .addHeader("Authorization", "Bearer $newToken")
            .build()

        try {
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 防止 disconnect 后 WS 才回 onOpen 造成的悬挂
                if (!sessionGate.isCurrent(session) || !shouldReconnect) {
                    webSocket.close(1000, "superseded")
                    return
                }
                isConnected = true
                connecting.set(false)
                reconnectDelayMs.set(INITIAL_RECONNECT_DELAY_MS)
                // 8.45 修复：不再在 onOpen 立即重置重连计数——「连接建立后立即被关闭」的循环
                // （服务端重启、握手成功即 1006/1011）会让 20 次上限永远达不到，退避封顶后
                // 无限重连耗电。改为连接稳定存活 30s 后才重置，异常循环仍受上限约束。
                val openedSession = session
                eventScope.launch {
                    kotlinx.coroutines.delay(30_000L)
                    if (isConnected && sessionGate.isCurrent(openedSession)) {
                        reconnectAttempts.set(0)
                    }
                }
                reconnectJob?.cancel()
                eventBus.post(WebSocketEvent.Connected(true))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!sessionGate.isCurrent(session)) return
                try {
                    val wsMsg = json.decodeFromString<WsMessage>(text)
                    handleWsMessage(wsMsg)
                } catch (e: Exception) {
                    emitError(WebSocketErrorKind.ENVELOPE_PARSE, e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (!sessionGate.isCurrent(session)) return
                isConnected = false
                connecting.set(false)
                eventBus.post(WebSocketEvent.Disconnected)
                // 1008 POLICY：封禁/登出/密码修改/会话失效 — 勿用旧 token 无限重连；
                // 同时立即触发登录失效流程（purge + 跳登录），避免停留在假登录态直到
                // 下一次 API 401（远端踢线/设备被删时用户应立刻感知）。
                // 例外一：reason 指明「会话已过期」（access token 到期，服务端 1008/1013 关闭）——
                // 这是可恢复状态，走刷新 token 后重连，绝不 purge 加密库。
                // 例外二（8.45 修复）：1008 但 reason 为空/无法解析 —— 先强制刷新 token 重连一次；
                // 刷新失败才放弃（REST 401 路径会兜底 purge）。原先直接 purge 会把
                // 「access token 到期但服务端只回裸 1008」误判为登录失效，强制用户重登。
                // 1008 + 「过期/token」reason：可恢复（刷新 token 后重连），不得视为登出。
                // 8.52 接入 isRecoverableExpiryReason（此前为死代码）：服务端通常用 1013 表示
                // 过期，但兼容旧实例仍发 1008 + 过期文案的情况。
                if (code == 1008 && reason != null && isRecoverableExpiryReason(reason) && shouldReconnect) {
                    refreshTokenThenReconnect()
                    return
                }
                if (code == 1008 && isAuthDeathReason(reason)) {
                    shouldReconnect = false
                    runCatching {
                        com.maodouchat.network.ApiService.notifyTokenExpired(
                            com.maodouchat.network.TokenManager.getInstanceOrNull()
                                ?.getUserId().orEmpty()
                        )
                    }
                    return
                }
                if (code == 1008 && reason.isBlank() && shouldReconnect) {
                    refreshTokenThenReconnect()
                    return
                }
                // 8.52 契约审计收尾：单用户连接数超限（服务端 1008）时立即重连只会叠加
                // 连接数——用固定长退避等待其他连接释放，而非默认指数退避的立即重连
                if (code == 1008 && reason != null && reason.contains("连接数超限") && shouldReconnect) {
                    scheduleReconnectWithBaseDelay(8_000L)
                    return
                }
                if (shouldReconnect) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!sessionGate.isCurrent(session)) return
                isConnected = false
                connecting.set(false)
                emitError(WebSocketErrorKind.CONNECTION, t)
                eventBus.post(WebSocketEvent.Disconnected)
                // 8.45 修复：WS 升级请求被 HTTP 401/403 拒绝（token 失效）——原先无视
                // response code 直接用旧 token 重试 20 次（约 10 分钟耗电）；改为先刷新
                // token 再重连；刷新失败则停止（REST 401 路径负责 purge）。
                if (response != null) {
                    val code = response.code
                    if (code == 401 || code == 403) {
                        if (!shouldReconnect) return
                        refreshTokenThenReconnect()
                        return
                    }
                    // 429 限流：尊重服务端 Retry-After（封顶 60s），否则退避封顶 30s 亦可
                    if (code == 429) {
                        val retryAfterMs = (response.header("Retry-After")?.toLongOrNull() ?: 30L)
                            .coerceIn(1L, 60L) * 1000L
                        if (shouldReconnect) scheduleReconnectWithBaseDelay(retryAfterMs)
                        return
                    }
                }
                // SSL 握手失败、DNS 解析失败等不会自动恢复的错误，停止重连避免持续耗电
                if (isNonRecoverableNetworkError(t)) {
                    shouldReconnect = false
                    return
                }
                if (shouldReconnect) scheduleReconnect()
            }
        })
        } catch (e: Exception) {
            // newWebSocket 同步失败时（如 URL 非法），重置 connecting 标志并调度重连
            connecting.set(false)
            isConnected = false
            emitError(WebSocketErrorKind.CONNECTION, e)
            if (isNonRecoverableNetworkError(e)) {
                shouldReconnect = false
            } else if (shouldReconnect) {
                scheduleReconnect()
            }
        }
    }

    /**
     * 判断是否为不可自动恢复的网络错误：SSL 握手/证书问题、连接被拒（ConnectException）。
     * 这些问题不会在重试中自动好转，持续重连只会耗电，应让用户介入。
     * 注意：UnknownHostException（DNS 解析失败）常为暂时性故障（网络切换、DNS 抖动），
     * 网络恢复后会自动好转，必须保持可恢复以自动重连，不应在此拦截。
     */
    private fun isNonRecoverableNetworkError(t: Throwable): Boolean {
        return t is javax.net.ssl.SSLException ||
            t is javax.net.ssl.SSLHandshakeException ||
            t is java.net.ConnectException
    }

    /**
     * WS 回调线程（OkHttp 读线程）里需要「刷新 token 后决定是否重连」时使用：
     * 把刷新放到 scope 协程执行，避免 runBlocking 阻塞 OkHttp 读线程（最多 30s 停摆）。
     * 刷新失败则停止重连（REST 401 路径负责 purge）。
     */
    private fun refreshTokenThenReconnect() {
        if (!shouldReconnect || reconnectJob?.isActive == true) return
        scope.launch {
            val refreshed = runCatching { com.maodouchat.network.ApiService.refreshAccessTokenForCurrentSession() }.getOrNull()
            if (refreshed != null && refreshed.isNotBlank()) {
                authToken.set(refreshed)
                scheduleReconnect()
            } else {
                shouldReconnect = false
            }
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect || reconnectJob?.isActive == true) return
        // 达到最大重连次数后停止，避免无限重连耗电
        val attempt = reconnectAttempts.incrementAndGet()
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            shouldReconnect = false
            reconnectAttempts.set(0)
            return
        }
        val url = serverUrl.get() ?: return
        val baseDelay = reconnectDelayMs.getAndUpdate { (it * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS) }
        scheduleReconnectAt(url, baseDelay)
    }

    /** 以指定基础延迟调度重连（如 429 Retry-After），仍计入重连尝试次数。 */
    private fun scheduleReconnectWithBaseDelay(baseDelayMs: Long) {
        if (!shouldReconnect || reconnectJob?.isActive == true) return
        val attempt = reconnectAttempts.incrementAndGet()
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            shouldReconnect = false
            reconnectAttempts.set(0)
            return
        }
        val url = serverUrl.get() ?: return
        scheduleReconnectAt(url, baseDelayMs.coerceAtMost(MAX_RECONNECT_DELAY_MS))
    }

    private fun scheduleReconnectAt(url: String, baseDelay: Long) {
        // 添加 ±20% jitter，防止服务器恢复时大量客户端同时重连（thundering herd）
        val jitter = (baseDelay * 0.2 * (Math.random() - 0.5) * 2).toLong()
        val delayMs = (baseDelay + jitter).coerceAtLeast(0)
        // 8.33：捕获失败时的会话代号，connect 时校验仍为当前会话（防登出/换号后旧 job 复活）
        val failedSession = sessionGate.current()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!shouldReconnect || isConnected) return@launch
            // Resolve after the delay: refresh may rotate the JWT while this job sleeps.
            // If TokenManager exists but was cleared by logout, never fall back to the old JWT.
            val tokenManager = TokenManager.getInstanceOrNull()
            var token = if (tokenManager != null) {
                tokenManager.getToken()?.takeIf(String::isNotBlank)
            } else {
                authToken.get()?.takeIf(String::isNotBlank)
            }
            // 断线关闭原因是 access token 到期时：重连前主动刷新（复用 REST 同一套 refresh 机制），
            // 避免用过期 JWT 重连被服务端再次 1008/1013 关闭（修复 15 分钟过期被强制登出的 CRITICAL）。
            // 本段已在协程内，直接挂起刷新（不再 runBlocking）。
            if (token != null && tokenManager != null) {
                val expiresAt = tokenManager.getAccessTokenExpiresAt()
                val stale = expiresAt > 0L && expiresAt <= System.currentTimeMillis() + 60_000L
                if (stale) {
                    val refreshed = runCatching { com.maodouchat.network.ApiService.refreshAccessTokenForCurrentSession() }.getOrNull()
                    if (refreshed != null && refreshed.isNotBlank()) {
                        token = refreshed
                    } else {
                        // 刷新失败：会话可能已失效，停止重连（避免用过期 JWT 反复 1008 空转）
                        shouldReconnect = false
                        return@launch
                    }
                }
            }
            if (token == null) {
                shouldReconnect = false
                return@launch
            }
            connect(url, token, isReconnect = true, reconnectSession = failedSession)
        }
    }

    private fun isAuthDeathReason(reason: String): Boolean {
        val r = reason.lowercase()
        // "expired" / "会话已过期" 是可恢复的（token 刷新后重连即可），不视为永久 auth death
        return r.contains("会话已失效") ||
            r.contains("已退出") ||
            r.contains("密码已修改") ||
            r.contains("账号已注销") ||
            r.contains("封禁") ||
            r.contains("已被移除") ||
            r.contains("被移除") ||
            r.contains("unauth") ||
            r.contains("revoked")
    }

    /** 1008/认证类关闭原因中可恢复的「访问令牌到期」子集：刷新 token 后重连即可，不得 purge。 */
    private fun isRecoverableExpiryReason(reason: String): Boolean {
        val r = reason.lowercase()
        return r.contains("过期") ||
            r.contains("expired") ||
            r.contains("expires") ||
            r.contains("token")
    }

    fun sendMessage(message: Message, sealedSenderCertificate: String? = null, silent: Boolean = message.parsedMeta().silent): Boolean {
        val sealed = message.sealedSender || !sealedSenderCertificate.isNullOrBlank()
        val payload = json.encodeToString(SendPayload.serializer(), SendPayload(
            id = message.id,
            chatId = message.chatId,
            content = message.content,
            type = message.type.name,
            sealedSender = sealed,
            silent = silent,
            sealedSenderCertificate = sealedSenderCertificate
        ))
        return send(WsMessage(type = "SEND_MESSAGE", payload = payload))
    }

    fun sendStatusUpdate(messageId: String, status: MessageStatus): Boolean {
        val payload = json.encodeToString(StatusPayload.serializer(), StatusPayload(
            messageId = messageId, status = status.name
        ))
        return send(WsMessage(type = "STATUS_UPDATE", payload = payload))
    }

    fun sendTyping(chatId: String, isTyping: Boolean): Boolean {
        val payload = json.encodeToString(TypingPayload.serializer(), TypingPayload("", chatId, isTyping))
        return send(WsMessage("TYPING", payload))
    }

    fun sendNudge(chatId: String, targetName: String): Boolean {
        val payload = json.encodeToString(NudgePayload.serializer(), NudgePayload(
            chatId = chatId, targetName = targetName
        ))
        return send(WsMessage(type = "NUDGE", payload = payload))
    }

    @Synchronized
    fun disconnect() {
        shouldReconnect = false
        sessionGate.invalidate()
        // 取消整个 parent Job 使所有重连子协程在下一挂起点退出，避免孤立协程继续调用 connect()
        connectionParent.cancel()
        reconnectJob = null
        reconnectDelayMs.set(INITIAL_RECONNECT_DELAY_MS)
        reconnectAttempts.set(0)
        serverUrl.set(null)
        authToken.set(null)
        webSocket?.close(1000, "signed out")
        webSocket = null
        isConnected = false
        // 必须重置 connecting 标志，否则登出后再登录时 connect() 的 CAS 会失败
        connecting.set(false)
    }

    fun isConnected(): Boolean = isConnected

    fun sendRaw(text: String): Boolean {
        if (!isConnected) return false
        return webSocket?.send(text) == true
    }

    private fun send(msg: WsMessage): Boolean {
        if (!isConnected) return false
        return webSocket?.send(json.encodeToString(WsMessage.serializer(), msg)) == true
    }

    private fun handleWsMessage(wsMsg: WsMessage) {
        when (wsMsg.type) {
            "ADMIN_BROADCAST" -> {
                try {
                    val data = json.decodeFromString<AdminBroadcastPayload>(wsMsg.payload)
                    eventBus.post(WebSocketEvent.AdminBroadcast(data.title, data.text, data.ts))
                } catch (e: Exception) {
                    // payload may be raw json object string already
                        runCatching {
                            val o = org.json.JSONObject(wsMsg.payload)
                            eventBus.post(
                                WebSocketEvent.AdminBroadcast(
                                    title = o.optString("title", "System"),
                                    text = o.optString("text").takeIf { it != "null" }.orEmpty(),
                                    ts = o.optLong("ts")
                                )
                            )
                        }.onFailure { emitError(WebSocketErrorKind.MESSAGE_PARSE, e) }
                }
            }
            "NEW_MESSAGE" -> {
                try {
                    val data = json.decodeFromString<IncomingMessage>(wsMsg.payload)
                    val message = Message(
                        id = data.id, chatId = data.chatId, senderId = data.senderId,
                        content = data.content, type = MessageType.fromWire(data.type),
                        timestamp = data.timestamp, status = MessageStatus.DELIVERED,
                        editedAt = data.editedAt, starred = data.starred, reactions = data.reactions,
                        expiresAt = data.expiresAt, sealedSender = data.sealedSender
                    )
                    eventBus.post(WebSocketEvent.MessageReceived(message))
                } catch (e: Exception) {
                    emitError(WebSocketErrorKind.MESSAGE_PARSE, e)
                }
            }
            "MESSAGE_STATUS" -> {
                try {
                    val data = json.decodeFromString<IncomingStatus>(wsMsg.payload)
                    eventBus.post(WebSocketEvent.StatusChanged(data.messageId, MessageStatus.fromWire(data.status)))
                } catch (e: Exception) {
                    emitError(WebSocketErrorKind.STATUS_PARSE, e)
                }
            }
            "CHAT_MARKED_READ" -> {
                try {
                    val data = json.decodeFromString<IncomingChatMarkedRead>(wsMsg.payload)
                    eventBus.post(WebSocketEvent.ChatMarkedRead(data.chatId))
                } catch (e: Exception) {
                    emitError(WebSocketErrorKind.MESSAGE_PARSE, e)
                }
            }
            "MESSAGE_DELETED" -> {
                try {
                    val data = json.decodeFromString<IncomingMessageDeleted>(wsMsg.payload)
                    eventBus.post(WebSocketEvent.MessageDeleted(data.messageId, data.chatId))
                } catch (e: Exception) {
                    emitError(WebSocketErrorKind.DELETE_PARSE, e)
                }
            }
            "MESSAGE_REVOKED" -> {
                try {
                    val data = json.decodeFromString<IncomingMessageDeleted>(wsMsg.payload)
                    eventBus.post(WebSocketEvent.MessageRevoked(data.messageId, data.chatId))
                } catch (e: Exception) {
                    emitError(WebSocketErrorKind.DELETE_PARSE, e)
                }
            }
            "MESSAGE_EDITED" -> {
                try {
                    val data = json.decodeFromString<IncomingMessageEdited>(wsMsg.payload)
                    eventBus.post(WebSocketEvent.MessageEdited(data.messageId, data.chatId, data.content, data.editedAt))
                } catch (e: Exception) {
                    emitError(WebSocketErrorKind.EDIT_PARSE, e)
                }
            }
            "MESSAGE_REACTION_UPDATED" -> {
                try {
                    val data = json.decodeFromString<IncomingMessageReactionUpdated>(wsMsg.payload)
                    eventBus.post(WebSocketEvent.MessageReactionUpdated(data.chatId, data.messageId, data.userId, data.reactions))
                } catch (e: Exception) {
                    emitError(WebSocketErrorKind.REACTION_PARSE, e)
                }
            }
            "PINNED_MESSAGES_UPDATED" -> {
                try {
                    val data = json.decodeFromString<IncomingPinnedMessagesUpdated>(wsMsg.payload)
                    eventBus.post(
                        WebSocketEvent.PinnedMessagesUpdated(
                            chatId = data.chatId,
                            actorId = data.actorId,
                            pins = data.pins
                        )
                    )
                } catch (e: Exception) {
                    emitError(WebSocketErrorKind.PIN_PARSE, e)
                }
            }
            "DISAPPEARING_MESSAGES_UPDATED" -> {
                try {
                    val data = json.decodeFromString<IncomingDisappearingMessagesUpdated>(wsMsg.payload)
                    eventBus.post(
                        WebSocketEvent.DisappearingMessagesUpdated(
                            chatId = data.chatId,
                            seconds = data.seconds,
                            updatedAt = data.updatedAt
                        )
                    )
                } catch (e: Exception) {
                    emitError(WebSocketErrorKind.DISAPPEARING_PARSE, e)
                }
            }
            "MESSAGE_EXPIRES" -> {
                try {
                    val data = json.decodeFromString<IncomingMessageExpires>(wsMsg.payload)
                    eventBus.post(
                        WebSocketEvent.MessageExpires(
                            messageId = data.messageId,
                            chatId = data.chatId,
                            expiresAt = data.expiresAt
                        )
                    )
                } catch (e: Exception) {
                    emitError(WebSocketErrorKind.EXPIRES_PARSE, e)
                }
            }
            "USER_STATUS" -> {
                try {
                    val data = json.decodeFromString<IncomingUserStatus>(wsMsg.payload)
                    eventBus.post(
                        WebSocketEvent.UserOnline(
                            userId = data.userId,
                            isOnline = data.isOnline,
                            lastSeen = data.lastSeen,
                            onlineRevoked = data.onlineRevoked,
                            statusRevoked = data.statusRevoked
                        )
                    )
                } catch (e: Exception) {
                    emitError(WebSocketErrorKind.USER_STATUS_PARSE, e)
                }
            }
            "USER_TYPING" -> {
                try {
                    val data = json.decodeFromString<IncomingTyping>(wsMsg.payload)
                    eventBus.post(WebSocketEvent.UserTyping(data.userId, data.chatId, data.isTyping))
                } catch (e: Exception) {
                    emitError(WebSocketErrorKind.TYPING_PARSE, e)
                }
            }

            "GROUP_REVISION_CHANGED" -> {
                try {
                    val data = json.decodeFromString<IncomingGroupRevisionChanged>(wsMsg.payload)
                    eventBus.post(
                        WebSocketEvent.GroupRevisionChanged(
                            chatId = data.chatId,
                            memberRevision = data.memberRevision,
                            reason = data.reason,
                            actorId = data.actorId,
                            targetUserId = data.targetUserId
                        )
                    )
                } catch (e: Exception) {
                    emitError(WebSocketErrorKind.GROUP_REVISION_PARSE, e)
                }
            }

            "FRIEND_REQUEST" -> {
                try {
                    val data = json.decodeFromString<IncomingFriendRequestEvent>(wsMsg.payload)
                    eventBus.post(WebSocketEvent.FriendRequestUpdated(data.action, data.request))
                } catch (e: Exception) {
                    emitError(WebSocketErrorKind.FRIEND_REQUEST_PARSE, e)
                }
            }

            "SIGNALING" -> {
                try {
                    val data = json.decodeFromString<IncomingSignaling>(wsMsg.payload)
                    eventBus.post(
                        WebSocketEvent.SignalingReceived(
                            data.fromUserId,
                            data.type,
                            data.payload,
                            data.callId,
                            data.groupId,
                            data.groupMemberIds,
                            data.groupInvite
                        )
                    )
                } catch (e: Exception) {
                    emitError(WebSocketErrorKind.SIGNALING_PARSE, e)
                }
            }

            "ERROR" -> {
                runCatching { json.decodeFromString<IncomingServerError>(wsMsg.payload) }
                    .onSuccess { error ->
                        eventBus.post(WebSocketEvent.ServerError(error.code, error.retryAfterSeconds, error.error))
                    }
                    .onFailure { error -> emitError(WebSocketErrorKind.ENVELOPE_PARSE, error) }
            }
        }
    }

    private fun emitError(kind: WebSocketErrorKind, cause: Throwable) {
        android.util.Log.w("WebSocketClient", "WebSocket failure: $kind", cause)
        eventBus.post(WebSocketEvent.Error(kind, cause.message))
    }
}
