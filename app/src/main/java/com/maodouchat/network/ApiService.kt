package com.maodouchat.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import com.maodouchat.network.api.ApiEndpointClients
import com.maodouchat.network.api.ApiSurface
import com.maodouchat.network.api.AuthApi
import com.maodouchat.network.api.AuthApiClient
import com.maodouchat.network.api.ConversationApi
import com.maodouchat.network.api.ConversationApiClient
import com.maodouchat.network.api.MediaApi
import com.maodouchat.network.api.MediaApiClient
import com.maodouchat.network.api.MessagingApi
import com.maodouchat.network.api.MessagingApiClient

enum class ApiFailureKind {
    HTTP,
    TIMEOUT,
    NETWORK,
    INVALID_RESPONSE,
    UNEXPECTED
}

class ApiException(
    val kind: ApiFailureKind,
    val statusCode: Int? = null,
    val serverMessage: String? = null,
    val serverCode: String? = null,
    /**
     * `true` means the request may already have reached the server. Callers that can incur a
     * charge or another non-idempotent side effect must require an explicit user retry.
     */
    val requestMayHaveReachedServer: Boolean = true,
    /** Server-provided Retry-After / body retryAfterSeconds when present. */
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null
) : Exception(serverMessage, cause)

/**
 * 登录/注册等面向用户的失败文案：HTTP 优先用服务端 message；
 * 网络/超时/无效响应没有 serverMessage（Exception.message 为 null），必须落到非空兜底。
 */
internal fun Throwable.toUserFacingMessage(
    networkMessage: String,
    timeoutMessage: String,
    invalidResponseMessage: String,
    fallbackMessage: String,
): String {
    if (this is ApiException) {
        val server = serverMessage?.trim().orEmpty()
        if (server.isNotEmpty()) return server
        return when (kind) {
            ApiFailureKind.TIMEOUT -> timeoutMessage
            ApiFailureKind.NETWORK -> networkMessage
            ApiFailureKind.INVALID_RESPONSE -> invalidResponseMessage
            ApiFailureKind.HTTP, ApiFailureKind.UNEXPECTED -> fallbackMessage
        }
    }
    return message?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackMessage
}

internal fun apiExceptionForIOException(error: java.io.IOException): ApiException {
    val connectionWasNeverEstablished = generateSequence<Throwable>(error) { it.cause }
        .any {
            it is java.net.UnknownHostException ||
                it is java.net.ConnectException ||
                it is java.net.NoRouteToHostException
        }
    return ApiException(
        kind = if (error is java.net.SocketTimeoutException) ApiFailureKind.TIMEOUT else ApiFailureKind.NETWORK,
        requestMayHaveReachedServer = !connectionWasNeverEstablished,
        cause = error
    )
}

internal object TokenExpiredEventPolicy {
    fun shouldHandle(
        eventOwnerUserId: String,
        eventSessionGeneration: Long,
        currentOwnerUserId: String?,
        currentSessionGeneration: Long,
    ): Boolean = eventOwnerUserId.isNotBlank() &&
        eventOwnerUserId == currentOwnerUserId &&
        eventSessionGeneration == currentSessionGeneration
}

/**
 * REST API client compatibility facade.
 *
 * Typed domain clients own endpoint construction; this object retains the shared authenticated
 * transport and preserves the existing static surface during call-site migration.
 */
object ApiService :
    ApiSurface by ApiEndpointClients,
    AuthApi by AuthApiClient,
    MessagingApi by MessagingApiClient,
    ConversationApi by ConversationApiClient,
    MediaApi by MediaApiClient {

    val auth: AuthApi = AuthApiClient
    val messaging: MessagingApi = MessagingApiClient
    val conversations: ConversationApi = ConversationApiClient
    val media: MediaApi = MediaApiClient

    internal val json = Json { ignoreUnknownKeys = true }
    // TokenManager is a process singleton holding applicationContext only. Reading it on
    // demand avoids keeping a second static strong reference in ApiService (lint/leak risk).
    private val tokenManager: TokenManager?
        get() = TokenManager.getInstanceOrNull()
    private val refreshMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    internal val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    internal const val ATTACHMENT_CHUNK_BYTES = 4L * 1024L * 1024L
    internal const val ATTACHMENT_CHUNK_MAX_ATTEMPTS = 3

    // 提前编译的高频正则，避免热路径上重复 Pattern.compile
    internal val ATTACHMENT_ID_REGEX = Regex("^att_[A-Za-z0-9_-]{20,100}$")
    internal val POST_IMAGE_FILENAME_REGEX = Regex("^[A-Za-z0-9_.-]+$")

    /**
     * Token 过期事件 — 当 API 返回 401 时发射，UI 层监听后清除 Token 并跳转登录页
     */
    data class TokenExpiredEvent(
        val ownerUserId: String,
        val sessionGeneration: Long,
    )

    // 8.49：replay=1——NavGraph 收集器随 Activity 重建（旋转/深色切换）时无收集器窗口内
    // tryEmit 的 401 事件会被直接丢弃，用户停留在已失效会话直到下一次 401
    private val _tokenExpired = MutableSharedFlow<TokenExpiredEvent>(replay = 1, extraBufferCapacity = 1)
    val tokenExpired: SharedFlow<TokenExpiredEvent> = _tokenExpired.asSharedFlow()

    /**
     * 非 401 来源的登录失效通知（如 WS 1008/踢线/设备被删）。
     * 复用同一事件流，UI 层走与 401 相同的 purge + 跳登录路径。
     */
    fun notifyTokenExpired(ownerUserId: String) {
        emitTokenExpired(ownerUserId)
    }

    private fun emitTokenExpired(ownerUserId: String) {
        _tokenExpired.tryEmit(
            TokenExpiredEvent(
                ownerUserId = ownerUserId,
                sessionGeneration = com.maodouchat.MaodouchatApp.currentSessionGeneration(),
            )
        )
    }

    /**
     * Drop process-local access token after logout / account switch.
     * Disk tokens are cleared by [TokenManager.clear]; this prevents a concurrent
     * refresh waiter from reusing the previous account's in-memory JWT.
     */
    fun clearSessionTokens() {
        memoryAccessToken = null
        memoryAccessTokenExpiresAt = 0L
        memoryAccessTokenOwnerUserId = null
    }

    /**
     * 安全读取响应体（只读一次，自动关闭）
     */
    private fun okhttp3.Response.readBodyAndClose(): String {
        return use { it.body?.string() ?: "" }
    }

    private fun parseErrorResponse(body: String): ErrorResponse? = try {
        json.decodeFromString(ErrorResponse.serializer(), body)
    } catch (e: Exception) {
        android.util.Log.w("ApiService", "parseError: non-JSON error body", e)
        null
    }

    internal fun parseError(body: String): String? {
        return parseErrorResponse(body)?.error?.takeIf { it.isNotBlank() }
    }

    private fun parseRetryAfterSeconds(body: String, headerValue: String? = null): Long? {
        val fromBody = parseErrorResponse(body)?.retryAfterSeconds?.takeIf { it > 0L }
        if (fromBody != null) return fromBody.coerceAtMost(86_400L)
        val raw = headerValue?.trim().orEmpty()
        if (raw.isEmpty()) return null
        raw.toLongOrNull()?.takeIf { it > 0L }?.let { return it.coerceAtMost(86_400L) }
        // HTTP-date Retry-After is rare for our APIs; ignore parse failures.
        return null
    }

    private fun apiExceptionFromHttp(
        statusCode: Int,
        body: String,
        retryAfterHeader: String? = null,
        url: String? = null
    ): ApiException {
        val error = parseErrorResponse(body)
        // 9.305：服务端错误必须带端点上下文——实测群分发 500 只有笼统「服务器内部错误」，
        // 无法定位是哪个接口炸的；4xx/5xx 一律记录（路径去 query 防敏感参数泄漏）
        if (statusCode >= 400) {
            android.util.Log.w("ApiService", "HTTP $statusCode ${url?.substringBefore('?') ?: "?"} code=${error?.code} msg=${error?.error} body=${body.take(200)}")
        }
        return ApiException(
            kind = ApiFailureKind.HTTP,
            statusCode = statusCode,
            serverMessage = error?.error,
            serverCode = error?.code,
            retryAfterSeconds = parseRetryAfterSeconds(body, retryAfterHeader)
        )
    }

    private data class HttpResult(val code: Int, val isSuccessful: Boolean, val body: String)

    private fun sessionChangedResult(): HttpResult = HttpResult(
        code = 409,
        isSuccessful = false,
        body = "{\"error\":\"session_changed\",\"code\":\"SESSION_CHANGED\"}"
    )

    private fun sessionChangedException(): ApiException = apiExceptionFromHttp(
        sessionChangedResult().code,
        sessionChangedResult().body
    )

    private fun accessTokenSubject(token: String?): String? = runCatching {
        val payload = token?.split('.')?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val decoded = android.util.Base64.decode(
            payload,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        org.json.JSONObject(String(decoded, Charsets.UTF_8)).optString("sub").trim().takeIf { it.isNotBlank() }
    }.getOrNull()

    private data class AuthenticatedRequestSession(
        val manager: TokenManager,
        val userId: String,
        val accessToken: String
    )

    private fun authenticatedRequestSession(request: Request): AuthenticatedRequestSession? {
        val authorization = request.header("Authorization")?.takeIf { it.isNotBlank() } ?: return null
        val accessToken = authorization.removePrefix("Bearer ").trim().takeIf { it.isNotBlank() } ?: return null
        val manager = tokenManager ?: return null
        val currentUserId = manager.getUserId()?.takeIf { it.isNotBlank() } ?: return null
        val requestUserId = accessTokenSubject(accessToken)
        val expectedUserId = requestUserId ?: currentUserId.takeIf { manager.getToken() == accessToken } ?: return null
        if (currentUserId != expectedUserId) return null
        return AuthenticatedRequestSession(manager, expectedUserId, accessToken)
    }

    private suspend fun executeRequest(request: Request): HttpResult =
        // Blocking OkHttp execute stays on IO. Never call this from Main.
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                try {
                    val response = call.execute()
                    val result = HttpResult(response.code, response.isSuccessful, response.readBodyAndClose())
                    if (continuation.isActive) continuation.resumeWith(Result.success(result))
                } catch (error: CancellationException) {
                    // Parent cancel must not resume failure as network error.
                    if (continuation.isActive) continuation.cancel(error)
                } catch (error: Exception) {
                    if (!continuation.isActive) return@suspendCancellableCoroutine
                    // OkHttp surfaces call.cancel() as IOException("Canceled"); treat as coroutine cancel.
                    if (call.isCanceled()) {
                        continuation.cancel()
                        return@suspendCancellableCoroutine
                    }
                    continuation.resumeWith(Result.failure(error))
                }
            }
        }

    private suspend fun executeWithRefresh(request: Request): HttpResult {
        val authorization = request.header("Authorization")
        if (authorization.isNullOrBlank()) return executeRequest(request)
        val session = authenticatedRequestSession(request) ?: return sessionChangedResult()
        val failedAccess = session.accessToken
        val manager = session.manager
        val expectedUserId = session.userId

        val firstResult = executeRequest(request)
        if (manager.getUserId() != expectedUserId) return sessionChangedResult()
        if (firstResult.code != 401) {
            return firstResult
        }

        when (val refresh = refreshAccessToken(failedAccessToken = failedAccess, expectedUserId = expectedUserId)) {
            is RefreshOutcome.Success -> {
                if (manager.getUserId() != expectedUserId) return sessionChangedResult()
                val retryRequest = request.newBuilder()
                    .header("Authorization", "Bearer ${refresh.token}")
                    .build()
                val retryResult = executeRequest(retryRequest)
                if (manager.getUserId() != expectedUserId) return sessionChangedResult()
                if (retryResult.code == 401) {
                    emitTokenExpired(expectedUserId)
                }
                return retryResult
            }
            RefreshOutcome.SessionDead -> {
                if (manager.getUserId() != expectedUserId) return sessionChangedResult()
                emitTokenExpired(expectedUserId)
                return firstResult
            }
            RefreshOutcome.TransientFailure -> {
                if (manager.getUserId() != expectedUserId) return sessionChangedResult()
                // 429/5xx/解析失败：保留会话，让调用方当普通失败处理，禁止清 E2EE 库
                return firstResult
            }
            RefreshOutcome.SessionChanged -> return sessionChangedResult()
        }
    }

    /** 并发 refresh 成功后的内存兜底（落盘失败或其它 waiter 复用） */
    @Volatile private var memoryAccessToken: String? = null
    @Volatile private var memoryAccessTokenExpiresAt: Long = 0L
    @Volatile private var memoryAccessTokenOwnerUserId: String? = null

    private sealed class RefreshOutcome {
        data class Success(val token: String) : RefreshOutcome()
        /** 401 / 明确会话作废：应 tokenExpired */
        data object SessionDead : RefreshOutcome()
        /** 429/5xx/网络抖动：不得清库 */
        data object TransientFailure : RefreshOutcome()
        /** 请求所属账号已不再是当前会话：静默丢弃，禁止重放或清理新会话。 */
        data object SessionChanged : RefreshOutcome()
    }

    private suspend fun refreshAccessToken(
        failedAccessToken: String? = null,
        expectedUserId: String
    ): RefreshOutcome = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
        val manager = tokenManager ?: return@withLock RefreshOutcome.SessionDead
        if (manager.getUserId() != expectedUserId) return@withLock RefreshOutcome.SessionChanged
        // 并发 waiter：若已有比失败 access 更新的 token，直接复用，避免二次 rotate / 误杀
        val mem = memoryAccessToken
        if (!mem.isNullOrBlank() &&
            memoryAccessTokenOwnerUserId == expectedUserId &&
            mem != failedAccessToken &&
            memoryAccessTokenExpiresAt > System.currentTimeMillis() + 5_000L
        ) {
            return@withLock RefreshOutcome.Success(mem)
        }
        val diskToken = manager.getToken()?.takeIf { it.isNotBlank() }
        val diskExp = manager.getAccessTokenExpiresAt()
        if (!diskToken.isNullOrBlank() &&
            accessTokenSubject(diskToken) == expectedUserId &&
            diskToken != failedAccessToken &&
            diskExp > System.currentTimeMillis() + 5_000L
        ) {
            memoryAccessToken = diskToken
            memoryAccessTokenExpiresAt = diskExp
            memoryAccessTokenOwnerUserId = expectedUserId
            return@withLock RefreshOutcome.Success(diskToken)
        }

        val refreshToken = manager.getRefreshToken()?.takeIf { it.isNotBlank() }
            ?: return@withLock RefreshOutcome.SessionDead
        val request = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/auth/refresh")
            .post(jsonBody(json.encodeToString(RefreshTokenRequest.serializer(), RefreshTokenRequest(refreshToken))))
            .build()
        val result = try {
            executeRequest(request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withLock RefreshOutcome.TransientFailure
        }
        if (manager.getUserId() != expectedUserId || manager.getRefreshToken() != refreshToken) {
            return@withLock RefreshOutcome.SessionChanged
        }
        if (!result.isSuccessful) {
            return@withLock when (result.code) {
                // 明确鉴权失败 / 会话作废
                401 -> RefreshOutcome.SessionDead
                // 封禁/限流/服务端错误：保留本地密钥
                403, 429 -> RefreshOutcome.TransientFailure
                in 500..599 -> RefreshOutcome.TransientFailure
                else -> RefreshOutcome.TransientFailure
            }
        }
        val auth = runCatching { json.decodeFromString(AuthResponse.serializer(), result.body) }.getOrNull()
            ?: return@withLock RefreshOutcome.TransientFailure
        if (auth.token.isBlank() || auth.refreshToken.isBlank()) {
            return@withLock RefreshOutcome.SessionDead
        }
        if (auth.userId != expectedUserId || accessTokenSubject(auth.token) != expectedUserId) {
            return@withLock RefreshOutcome.SessionDead
        }
        val saveResult = manager.saveAuthSessionIfCurrent(
            expectedUserId = expectedUserId,
            expectedRefreshToken = refreshToken,
            token = auth.token,
            refreshToken = auth.refreshToken,
            userId = auth.userId,
            accessTokenExpiresAt = auth.expiresAt,
            refreshTokenExpiresAt = auth.refreshExpiresAt
        )
        if (saveResult == TokenManager.ConditionalSessionSaveResult.SESSION_CHANGED) {
            return@withLock RefreshOutcome.SessionChanged
        }
        // 服务端已轮换 refresh：本地落盘失败时仍返回内存 token，避免误发 tokenExpired 清库
        if (saveResult == TokenManager.ConditionalSessionSaveResult.WRITE_FAILED) {
            android.util.Log.w("ApiService", "refreshAccessToken: saveAuthSession failed; using in-memory token")
        }
        if (manager.getUserId() != expectedUserId) return@withLock RefreshOutcome.SessionChanged
        memoryAccessToken = auth.token
        memoryAccessTokenExpiresAt = auth.expiresAt
        memoryAccessTokenOwnerUserId = expectedUserId
        // REST 刷新后立刻让 WS 用新 JWT 重连，避免 scheduleReconnect 继续用过期 token。
        // 9.234：传 isReconnect=true + 当前会话代号——此前默认 isReconnect=false 走「手动连接」
        // 语义，绕过 disconnect() 的 shouldReconnect 守卫，登出瞬间晚到的 refresh 会拿旧账号
        // 新 token 复活连接（旧账号持续在线污染事件流）；重连预算也得以继承而非被清零。
        runCatching {
            WebSocketClient.connect(ApiConfig.WS_URL, auth.token, isReconnect = true)
        }
        RefreshOutcome.Success(auth.token)
        }
    }

    /**
     * WebSocket 重连专用：为当前会话刷新 access token。
     * 复用 executeWithRefresh 同一套 refresh 机制（并发互斥 + 内存/落盘兜底）；
     * 成功返回新 token，会话已死/网络失败返回 null（WS 保持重试，REST 401 路径负责兜底清库）。
     */
    suspend fun refreshAccessTokenForCurrentSession(): String? {
        val manager = tokenManager ?: return null
        val userId = manager.getUserId() ?: return null
        if (manager.getUserId() != userId) return null
        return when (val outcome = refreshAccessToken(expectedUserId = userId)) {
            is RefreshOutcome.Success -> outcome.token
            else -> null
        }
    }

    internal suspend fun <T> send(request: Request, serializer: kotlinx.serialization.KSerializer<T>): Result<T> = withContext(Dispatchers.IO) {
        try {
            val response = executeWithRefresh(request)
            if (response.isSuccessful) {
                Result.success(json.decodeFromString(serializer, response.body))
            } else {
                Result.failure(apiExceptionFromHttp(response.code, response.body, url = request.url.toString()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(apiExceptionForIOException(e))
        } catch (e: java.io.IOException) {
            Result.failure(apiExceptionForIOException(e))
        } catch (e: kotlinx.serialization.SerializationException) {
            Result.failure(ApiException(ApiFailureKind.INVALID_RESPONSE, cause = e))
        } catch (e: ApiException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(ApiException(ApiFailureKind.UNEXPECTED, cause = e))
        }
    }

    internal suspend fun sendUnit(request: Request): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = executeWithRefresh(request)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(apiExceptionFromHttp(response.code, response.body, url = request.url.toString()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(apiExceptionForIOException(e))
        } catch (e: java.io.IOException) {
            Result.failure(apiExceptionForIOException(e))
        } catch (e: ApiException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(ApiException(ApiFailureKind.UNEXPECTED, cause = e))
        }
    }

    /**
     * Blocking OkHttp execute with cancel registration. Caller owns [Response.close].
     * On cancel, the call is aborted so large attachment downloads do not keep reading.
     */
    private suspend fun executeStreamingCall(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            try {
                val response = call.execute()
                if (continuation.isActive) {
                    continuation.resumeWith(Result.success(response))
                } else {
                    response.close()
                }
            } catch (error: CancellationException) {
                if (continuation.isActive) continuation.cancel(error)
            } catch (error: Exception) {
                if (!continuation.isActive) return@suspendCancellableCoroutine
                if (call.isCanceled()) {
                    continuation.cancel()
                    return@suspendCancellableCoroutine
                }
                continuation.resumeWith(Result.failure(error))
            }
        }

    internal suspend fun executeStreamingWithRefresh(request: Request): Response {
        val hasAuthorization = !request.header("Authorization").isNullOrBlank()
        val session = authenticatedRequestSession(request)
        if (hasAuthorization && session == null) throw sessionChangedException()
        var response = executeStreamingCall(request)
        if (session != null && session.manager.getUserId() != session.userId) {
            response.close()
            throw sessionChangedException()
        }
        if (response.code != 401 || session == null) return response
        return when (val refresh = refreshAccessToken(
            failedAccessToken = session.accessToken,
            expectedUserId = session.userId
        )) {
            is RefreshOutcome.Success -> {
                if (session.manager.getUserId() != session.userId) {
                    response.close()
                    throw sessionChangedException()
                }
                response.close()
                response = executeStreamingCall(
                    request.newBuilder().header("Authorization", "Bearer ${refresh.token}").build()
                )
                if (session.manager.getUserId() != session.userId) {
                    response.close()
                    throw sessionChangedException()
                }
                if (response.code == 401) emitTokenExpired(session.userId)
                response
            }
            RefreshOutcome.SessionDead -> {
                if (session.manager.getUserId() != session.userId) {
                    response.close()
                    throw sessionChangedException()
                }
                emitTokenExpired(session.userId)
                response
            }
            RefreshOutcome.TransientFailure -> response
            RefreshOutcome.SessionChanged -> {
                response.close()
                throw sessionChangedException()
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun jsonBody(value: String) = value.toRequestBody(JSON_MEDIA)

    /** 阻塞 OkHttp 调用统一切到 IO 线程，避免从 Main 调度器调用时 NetworkOnMainThreadException。 */
    internal suspend fun executeForText(req: Request, errorPrefix: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                // 8.61：带 Authorization 的请求走 executeWithRefresh——401 自动刷新 + 触发 tokenExpired，
                // 避免 TOTP/密封发件人证书等端点绕过刷新（会话 15min 过期后停留页面呈假登录态且错误原文透出）
                val result = if (req.header("Authorization").isNullOrBlank()) {
                    executeRequest(req)
                } else {
                    executeWithRefresh(req)
                }
                if (!result.isSuccessful) error("${errorPrefix}_${result.code}:${result.body}")
                Result.success(result.body)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

}
