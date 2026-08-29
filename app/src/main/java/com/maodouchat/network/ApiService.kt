package com.maodouchat.network

import com.maodouchat.BuildConfig
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.MessageReaction
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
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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
 * REST API 客户端
 */
object ApiService {

    private val json = Json { ignoreUnknownKeys = true }
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

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private const val ATTACHMENT_CHUNK_BYTES = 4L * 1024L * 1024L
    private const val ATTACHMENT_CHUNK_MAX_ATTEMPTS = 3

    // 提前编译的高频正则，避免热路径上重复 Pattern.compile
    private val ATTACHMENT_ID_REGEX = Regex("^att_[A-Za-z0-9_-]{20,100}$")
    private val POST_IMAGE_FILENAME_REGEX = Regex("^[A-Za-z0-9_.-]+$")

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

    private fun parseError(body: String): String? {
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

    private suspend fun <T> send(request: Request, serializer: kotlinx.serialization.KSerializer<T>): Result<T> = withContext(Dispatchers.IO) {
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

    private suspend fun sendUnit(request: Request): Result<Unit> = withContext(Dispatchers.IO) {
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

    private suspend fun executeStreamingWithRefresh(request: Request): Response {
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

    private class FileChunkRequestBody(
        private val file: File,
        private val offset: Long,
        private val length: Long,
        private val onProgress: (Long, Long) -> Unit
    ) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            val total = file.length()
            var written = 0L
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (written < length) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), length - written).toInt())
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    written += read
                    onProgress(offset + written, total)
                }
                require(written == length) { "attachment_chunk_source_changed" }
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun jsonBody(value: String) = value.toRequestBody(JSON_MEDIA)

    /** 阻塞 OkHttp 调用统一切到 IO 线程，避免从 Main 调度器调用时 NetworkOnMainThreadException。 */
    private suspend fun executeForText(req: Request, errorPrefix: String): Result<String> =
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

    suspend fun getTotpStatus(token: String): Result<String> = executeForText(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/auth/totp/status")
            .header("Authorization", "Bearer $token")
            .get()
            .build(),
        "totp_status"
    )

    suspend fun getSealedSenderCertificate(token: String, deviceId: Int = 1): Result<String> = executeForText(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/e2ee/sealed-sender/certificate?deviceId=$deviceId")
            .header("Authorization", "Bearer $token")
            .get()
            .build(),
        "sealed_sender_cert"
    )

    suspend fun getPublicStatus(): Result<String> = executeForText(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/public/status")
            .get()
            .build(),
        "public_status"
    )

    suspend fun setupTotp(token: String): Result<String> = executeForText(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/auth/totp/setup")
            .header("Authorization", "Bearer $token")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build(),
        "totp_setup"
    )

    /** 0.77：查询 2FA 是否已启用。 */
    suspend fun totpStatus(token: String): Result<Boolean> = try {
        val body = executeForText(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/auth/totp/status")
                .header("Authorization", "Bearer $token")
                .get()
                .build(),
            "totp_status"
        ).getOrThrow()
        Result.success(runCatching { org.json.JSONObject(body).optBoolean("enabled", false) }.getOrDefault(false))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    /** 0.77：验证当前 TOTP 后重新生成恢复码（返回新恢复码）。 */
    suspend fun regenerateTotpCodes(token: String, code: String): Result<List<String>> = try {
        val body = executeForText(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/auth/totp/recover-codes")
                .header("Authorization", "Bearer $token")
                .post(org.json.JSONObject().put("code", code).toString().toRequestBody("application/json".toMediaType()))
                .build(),
            "totp_recover"
        ).getOrThrow()
        val obj = runCatching { org.json.JSONObject(body) }.getOrNull()
        val codes = obj?.optJSONArray("backupCodes")
        val result = if (codes != null) {
            (0 until codes.length()).map { codes.optString(it) }.filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        Result.success(result)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    suspend fun confirmTotp(token: String, code: String): Result<List<String>> = try {
        val body = executeForText(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/auth/totp/confirm")
                .header("Authorization", "Bearer $token")
                .post(org.json.JSONObject().put("code", code).toString().toRequestBody("application/json".toMediaType()))
                .build(),
            "totp_confirm"
        ).getOrThrow()
        // 0.75：解析恢复码（启用成功后一次性返回）
        val obj = runCatching { org.json.JSONObject(body) }.getOrNull()
        val codes = obj?.optJSONArray("backupCodes")
        val result = if (codes != null) {
            (0 until codes.length()).map { codes.optString(it) }.filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        Result.success(result)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    suspend fun disableTotp(token: String, code: String): Result<String> = executeForText(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/auth/totp/disable")
            .header("Authorization", "Bearer $token")
            .post(org.json.JSONObject().put("code", code).toString().toRequestBody("application/json".toMediaType()))
            .build(),
        "totp_disable"
    )

suspend fun login(email: String, password: String, totpCode: String = ""): Result<AuthResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/auth/login").post(jsonBody(json.encodeToString(LoginRequest.serializer(), LoginRequest(email, password, totpCode)))).build(), AuthResponse.serializer())

    suspend fun register(name: String, email: String, password: String): Result<AuthResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/auth/register").post(jsonBody(json.encodeToString(RegisterRequest.serializer(), RegisterRequest(name, email, password)))).build(), AuthResponse.serializer())

    suspend fun logout(refreshToken: String, accessToken: String? = null, deviceId: String = ""): Result<Unit> {
        val builder = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/auth/logout")
            .post(
                jsonBody(
                    json.encodeToString(
                        RefreshTokenRequest.serializer(),
                        RefreshTokenRequest(refreshToken, deviceId = deviceId)
                    )
                )
            )
        if (!accessToken.isNullOrBlank()) {
            builder.addHeader("Authorization", "Bearer $accessToken")
        }
        return sendUnit(builder.build())
    }

    suspend fun logoutAll(token: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/auth/logout-all").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build())

    suspend fun getChats(token: String): Result<List<ChatDto>> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(ChatDto.serializer()))

    suspend fun updateChatSettings(token: String, chatId: String, request: UpdateChatSettingsRequest): Result<ChatSettingsResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/settings").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateChatSettingsRequest.serializer(), request))).build(), ChatSettingsResponse.serializer())

    suspend fun updateDisappearingMessages(
        token: String,
        chatId: String,
        seconds: Int
    ): Result<DisappearingMessagesResponse> =
        send(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/chats/$chatId/disappearing-messages")
                .addHeader("Authorization", "Bearer $token")
                .put(jsonBody(json.encodeToString(UpdateDisappearingMessagesRequest.serializer(), UpdateDisappearingMessagesRequest(seconds))))
                .build(),
            DisappearingMessagesResponse.serializer()
        )

    suspend fun sendMessageV2(
        token: String,
        request: SendMessageRequestV2,
    ): Result<SendMessageResponseV2> = send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/v2/messages")
            .addHeader("Authorization", "Bearer $token")
            .post(jsonBody(json.encodeToString(SendMessageRequestV2.serializer(), request)))
            .build(),
        SendMessageResponseV2.serializer(),
    )

    suspend fun getConversationSnapshotV2(
        token: String,
        conversationId: String,
    ): Result<ConversationSnapshotV2Dto> = send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/v2/conversations/${java.net.URLEncoder.encode(conversationId, Charsets.UTF_8.name())}/snapshot")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        ConversationSnapshotV2Dto.serializer(),
    )

    suspend fun getPendingInboxV2(
        token: String,
        limit: Int = 100,
    ): Result<PendingInboxResponseV2> = send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/v2/inbox?limit=${limit.coerceIn(1, 500)}")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        PendingInboxResponseV2.serializer(),
    )

    suspend fun acknowledgeInboxV2(
        token: String,
        envelopeIds: List<String>,
    ): Result<AcknowledgeEnvelopesResponseV2> = send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/v2/inbox/ack")
            .addHeader("Authorization", "Bearer $token")
            .post(
                jsonBody(
                    json.encodeToString(
                        AcknowledgeEnvelopesRequestV2.serializer(),
                        AcknowledgeEnvelopesRequestV2(envelopeIds),
                    ),
                ),
            )
            .build(),
        AcknowledgeEnvelopesResponseV2.serializer(),
    )

    suspend fun uploadEncryptedAttachment(
        token: String,
        chatId: String,
        messageId: String,
        encryptedFile: File,
        cipherSha256: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onCheckpoint: suspend (String, Long, Long) -> Unit = { _, _, _ -> }
    ): Result<AttachmentUploadResponse> = withContext(Dispatchers.IO) {
        try {
            val total = encryptedFile.length()
            require(total in 17L..(100L * 1024L * 1024L + 64L)) { "attachment_size_invalid" }
            require(encryptedFile.sha256Hex() == cipherSha256.lowercase()) { "attachment_source_hash_mismatch" }
            var status = createAttachmentUploadSessionWithRetry(token, chatId, messageId, cipherSha256, total)
            validateAttachmentUploadStatus(status, cipherSha256, total)
            var attachmentId = status.id
            onCheckpoint(attachmentId, status.uploadedBytes, total)
            onProgress(status.uploadedBytes, total)
            while (!status.complete) {
                currentCoroutineContext().ensureActive()
                val offset = status.uploadedBytes
                // 服务端 DB 进度可能滞后于文件写入（appendChunk 与进度更新乱序）：offset==total
                // 时重新拉取状态自愈（服务端 reconcile 会补齐 UPLOADED），不得当作非法偏移失败；
                // 但自愈轮询设上限，避免服务端异常时忙等
                if (offset >= total) {
                    var revalidated: AttachmentUploadStatusResponse? = null
                    for (i in 0 until 3) {
                        currentCoroutineContext().ensureActive()
                        val attempt = getAttachmentUploadStatus(token, attachmentId).getOrNull()
                        if (attempt == null) {
                            kotlinx.coroutines.delay(500L)
                            continue
                        }
                        validateAttachmentUploadStatus(attempt, cipherSha256, total)
                        if (attempt.id != attachmentId) attachmentId = attempt.id
                        status = attempt
                        if (status.complete || status.uploadedBytes < total) {
                            revalidated = attempt
                            break
                        }
                        kotlinx.coroutines.delay(500L)
                    }
                    if (revalidated == null) {
                        throw ApiException(ApiFailureKind.NETWORK, serverMessage = "attachment_status_unavailable")
                    }
                    if (status.complete) break
                    continue
                }
                require(offset >= 0) { "attachment_upload_offset_invalid" }
                val length = minOf(ATTACHMENT_CHUNK_BYTES, total - offset)
                val chunkHash = encryptedFile.sha256Hex(offset, length)
                var lastError: Throwable? = null
                var advanced = false
                for (attempt in 0 until ATTACHMENT_CHUNK_MAX_ATTEMPTS) {
                    val chunkResult = uploadAttachmentChunk(
                        token = token,
                        attachmentId = attachmentId,
                        encryptedFile = encryptedFile,
                        offset = offset,
                        length = length,
                        chunkSha256 = chunkHash,
                        onProgress = onProgress
                    )
                    if (chunkResult.isSuccess) {
                        status = chunkResult.getOrThrow()
                        validateAttachmentUploadStatus(status, cipherSha256, total)
                        // 8.33 修复：服务端按 messageId 幂等可能替换上传会话（verify 路径有
                        // 410 attachment_session_replaced 语义）。此前 require 抛 IllegalArgumentException
                        // 被兜底归为不可重试，传输被永久标记失败。改为重新锚定新会话继续上传
                        // （新会话 uploadedBytes 通常归零，从 0 续传，正确性不受影响）。
                        if (status.id != attachmentId) attachmentId = status.id
                        advanced = status.uploadedBytes > offset || status.complete
                        if (advanced) break
                    }
                    if (!advanced) {
                        lastError = chunkResult.exceptionOrNull()
                        val recovered = getAttachmentUploadStatus(token, attachmentId).getOrNull()
                        if (recovered != null) {
                            validateAttachmentUploadStatus(recovered, cipherSha256, total)
                            if (recovered.id != attachmentId) attachmentId = recovered.id
                            status = recovered
                            if (status.uploadedBytes > offset || status.complete) {
                                advanced = true
                                break
                            }
                        }
                    }
                }
                if (!advanced) throw lastError ?: ApiException(ApiFailureKind.NETWORK, serverMessage = "attachment_chunk_failed")
                onCheckpoint(attachmentId, status.uploadedBytes, total)
                onProgress(status.uploadedBytes, total)
            }
            require(status.uploadedBytes == total) { "attachment_upload_incomplete" }
            Result.success(
                AttachmentUploadResponse(attachmentId, status.cipherSha256, status.cipherSize, status.expiresAt)
            )
        } catch (error: CancellationException) {
            // runCatching/recoverCatching would wrap cancel as Result.failure / UNEXPECTED
            throw error
        } catch (error: ApiException) {
            Result.failure(error)
        } catch (error: java.net.SocketTimeoutException) {
            Result.failure(ApiException(ApiFailureKind.TIMEOUT, cause = error))
        } catch (error: java.io.IOException) {
            Result.failure(ApiException(ApiFailureKind.NETWORK, cause = error))
        } catch (error: Exception) {
            Result.failure(ApiException(ApiFailureKind.UNEXPECTED, cause = error))
        }
    }

    private suspend fun createAttachmentUploadSession(
        token: String,
        chatId: String,
        messageId: String,
        cipherSha256: String,
        cipherSize: Long
    ): Result<AttachmentUploadStatusResponse> = send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/attachment-uploads")
            .addHeader("Authorization", "Bearer $token")
            .post(jsonBody(json.encodeToString(
                AttachmentUploadSessionRequest.serializer(),
                AttachmentUploadSessionRequest(chatId, messageId, cipherSha256, cipherSize)
            )))
            .build(),
        AttachmentUploadStatusResponse.serializer()
    )

    private suspend fun createAttachmentUploadSessionWithRetry(
        token: String,
        chatId: String,
        messageId: String,
        cipherSha256: String,
        cipherSize: Long
    ): AttachmentUploadStatusResponse {
        var lastError: Throwable? = null
        repeat(ATTACHMENT_CHUNK_MAX_ATTEMPTS) {
            val result = createAttachmentUploadSession(token, chatId, messageId, cipherSha256, cipherSize)
            if (result.isSuccess) return result.getOrThrow()
            lastError = result.exceptionOrNull()
            val retryable = (lastError as? ApiException)?.kind in setOf(ApiFailureKind.NETWORK, ApiFailureKind.TIMEOUT)
            if (!retryable) throw lastError ?: ApiException(ApiFailureKind.UNEXPECTED)
        }
        throw lastError ?: ApiException(ApiFailureKind.NETWORK, serverMessage = "attachment_session_failed")
    }

    private suspend fun getAttachmentUploadStatus(token: String, attachmentId: String): Result<AttachmentUploadStatusResponse> =
        send(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/attachment-uploads/$attachmentId")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build(),
            AttachmentUploadStatusResponse.serializer()
        )

    suspend fun verifyEncryptedAttachmentReady(
        token: String,
        chatId: String,
        messageId: String,
        attachmentId: String,
        expectedSha256: String,
        expectedSize: Long
    ): Result<AttachmentUploadStatusResponse> {
        val current = getAttachmentUploadStatus(token, attachmentId)
        if (current.isFailure) return Result.failure(current.exceptionOrNull()!!)
        return try {
            val status = current.getOrThrow()
            validateAttachmentUploadStatus(status, expectedSha256, expectedSize)
            require(status.complete && status.status in setOf("UPLOADED", "COMMITTED")) { "attachment_upload_incomplete" }
            if (status.status == "COMMITTED") return Result.success(status)

            val refreshed = createAttachmentUploadSession(
                token = token,
                chatId = chatId,
                messageId = messageId,
                cipherSha256 = expectedSha256,
                cipherSize = expectedSize
            ).getOrThrow()
            validateAttachmentUploadStatus(refreshed, expectedSha256, expectedSize)
            if (refreshed.id != attachmentId || !refreshed.complete || refreshed.status != "UPLOADED") {
                throw ApiException(ApiFailureKind.HTTP, 410, "attachment_session_replaced")
            }
            Result.success(refreshed)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun uploadAttachmentChunk(
        token: String,
        attachmentId: String,
        encryptedFile: File,
        offset: Long,
        length: Long,
        chunkSha256: String,
        onProgress: (Long, Long) -> Unit
    ): Result<AttachmentUploadStatusResponse> = send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/attachment-uploads/$attachmentId?offset=$offset")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("X-Chunk-SHA256", chunkSha256)
            .put(FileChunkRequestBody(encryptedFile, offset, length, onProgress))
            .build(),
        AttachmentUploadStatusResponse.serializer()
    )

    private fun validateAttachmentUploadStatus(
        status: AttachmentUploadStatusResponse,
        expectedSha256: String,
        expectedSize: Long
    ) {
        require(status.id.matches(ATTACHMENT_ID_REGEX)) { "attachment_id_invalid" }
        require(status.cipherSha256 == expectedSha256.lowercase()) { "attachment_hash_mismatch" }
        require(status.cipherSize == expectedSize) { "attachment_size_mismatch" }
        require(status.uploadedBytes in 0L..expectedSize) { "attachment_offset_invalid" }
        require(status.status in setOf("UPLOADING", "UPLOADED", "COMMITTED")) { "attachment_status_invalid" }
        require(!status.complete || status.uploadedBytes == expectedSize) { "attachment_completion_invalid" }
    }

    suspend fun deleteUncommittedAttachment(token: String, attachmentId: String): Result<Unit> =
        sendUnit(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/attachments/$attachmentId")
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()
        )

    suspend fun downloadEncryptedAttachment(
        token: String,
        attachmentId: String,
        expectedSha256: String,
        expectedSize: Long,
        target: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            target.parentFile?.mkdirs()
            if (target.length() > expectedSize) target.delete()
            if (target.length() == expectedSize) {
                if (target.sha256Hex() == expectedSha256.lowercase()) {
                    onProgress(expectedSize, expectedSize)
                    return@withContext Result.success(Unit)
                }
                target.delete()
            }
            var start = target.length()
            try {
                downloadEncryptedAttachmentPass(token, attachmentId, expectedSha256, expectedSize, target, start, onProgress)
            } catch (error: ApiException) {
                if (start > 0L && error.kind == ApiFailureKind.INVALID_RESPONSE) {
                    target.delete()
                    start = 0L
                    downloadEncryptedAttachmentPass(token, attachmentId, expectedSha256, expectedSize, target, start, onProgress)
                } else {
                    throw error
                }
            }
            if (target.length() != expectedSize || target.sha256Hex() != expectedSha256.lowercase()) {
                if (start > 0L) {
                    target.delete()
                    start = 0L
                    downloadEncryptedAttachmentPass(token, attachmentId, expectedSha256, expectedSize, target, start, onProgress)
                }
            }
            if (target.length() != expectedSize || target.sha256Hex() != expectedSha256.lowercase()) {
                target.delete()
                throw ApiException(ApiFailureKind.INVALID_RESPONSE, serverMessage = "attachment_integrity_failed")
            }
            onProgress(expectedSize, expectedSize)
            Result.success(Unit)
        } catch (error: CancellationException) {
            // Must rethrow: recoverCatching previously wrapped cancel as UNEXPECTED failure
            // and UI would show download-failed instead of just clearing spinner.
            // 8.33 修复：取消时清理 .part 残片，避免依赖 48h 兜底清理前滞留 100MB+ 缓存
            runCatching { target.delete() }
            throw error
        } catch (error: ApiException) {
            if (error.kind == ApiFailureKind.INVALID_RESPONSE) target.delete()
            Result.failure(error)
        } catch (error: java.net.SocketTimeoutException) {
            Result.failure(ApiException(ApiFailureKind.TIMEOUT, cause = error))
        } catch (error: java.io.IOException) {
            Result.failure(ApiException(ApiFailureKind.NETWORK, cause = error))
        } catch (error: Exception) {
            Result.failure(ApiException(ApiFailureKind.UNEXPECTED, cause = error))
        }
    }

    /** 1.127：下载动态图片（认证路由 /api/files/post-image/...）到本地文件。 */
    suspend fun downloadPostImage(token: String, imageUrl: String, target: java.io.File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            target.parentFile?.mkdirs()
            target.delete()
            val request = Request.Builder()
                .url(imageUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            executeStreamingWithRefresh(request).use { response ->
                if (!response.isSuccessful) {
                    throw ApiException(ApiFailureKind.HTTP, response.code, parseError(response.body?.string().orEmpty()))
                }
                val body = response.body ?: throw ApiException(ApiFailureKind.INVALID_RESPONSE, serverMessage = "post_image_body_missing")
                body.byteStream().use { input ->
                    java.io.FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }
            if (!target.exists() || target.length() == 0L) {
                target.delete()
                throw ApiException(ApiFailureKind.INVALID_RESPONSE, serverMessage = "post_image_empty")
            }
            Result.success(Unit)
        } catch (error: CancellationException) {
            runCatching { target.delete() }
            throw error
        } catch (error: Exception) {
            runCatching { target.delete() }
            Result.failure(error)
        }
    }

    private suspend fun downloadEncryptedAttachmentPass(
        token: String,
        attachmentId: String,
        expectedSha256: String,
        expectedSize: Long,
        target: File,
        requestedStart: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        val requestBuilder = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/attachments/$attachmentId")
            .addHeader("Authorization", "Bearer $token")
            .get()
        if (requestedStart > 0L) requestBuilder.addHeader("Range", "bytes=$requestedStart-")
        executeStreamingWithRefresh(requestBuilder.build()).use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw ApiException(ApiFailureKind.HTTP, response.code, parseError(body))
            }
            if (response.header("X-Content-SHA256")?.lowercase() != expectedSha256.lowercase()) {
                throw ApiException(ApiFailureKind.INVALID_RESPONSE, serverMessage = "attachment_hash_header_mismatch")
            }
            val append = requestedStart > 0L && response.code == 206
            val actualStart = if (append) requestedStart else 0L
            if (append) {
                val expectedRange = "bytes $requestedStart-${expectedSize - 1}/$expectedSize"
                if (response.header("Content-Range") != expectedRange) {
                    throw ApiException(ApiFailureKind.INVALID_RESPONSE, serverMessage = "attachment_range_mismatch")
                }
            }
            val body = response.body ?: throw ApiException(ApiFailureKind.INVALID_RESPONSE, serverMessage = "attachment_body_missing")
            val expectedBodySize = expectedSize - actualStart
            if (body.contentLength() >= 0L && body.contentLength() != expectedBodySize) {
                throw ApiException(ApiFailureKind.INVALID_RESPONSE, serverMessage = "attachment_size_header_mismatch")
            }
            var copied = 0L
            body.byteStream().use { input ->
                FileOutputStream(target, append).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > expectedBodySize) {
                            target.delete()
                            throw ApiException(ApiFailureKind.INVALID_RESPONSE, serverMessage = "attachment_size_exceeded")
                        }
                        output.write(buffer, 0, read)
                        onProgress(actualStart + copied, expectedSize)
                    }
                }
            }
            if (copied != expectedBodySize) {
                throw ApiException(ApiFailureKind.NETWORK, serverMessage = "attachment_download_incomplete")
            }
        }
    }

    private fun File.sha256Hex(): String = sha256Hex(0L, length())

    private fun File.sha256Hex(offset: Long, length: Long): String {
        require(offset >= 0L && length >= 0L && offset + length <= this.length())
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(this, "r").use { input ->
            input.seek(offset)
            var remaining = length
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (remaining > 0L) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                digest.update(buffer, 0, read)
                remaining -= read
            }
            require(remaining == 0L) { "attachment_source_changed" }
        }
        return digest.digest().toHex()
    }

    suspend fun createChat(
        token: String,
        participantIds: List<String>,
        isGroup: Boolean = false,
        groupName: String? = null,
        chatType: String? = null
    ): Result<ChatDto> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(CreateChatRequest.serializer(), CreateChatRequest(participantIds, isGroup, groupName, chatType)))).build(), ChatDto.serializer())

    suspend fun addGroupMembers(token: String, chatId: String, memberIds: List<String>): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/members").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(GroupMembersRequest.serializer(), GroupMembersRequest(memberIds)))).build())

    suspend fun removeGroupMember(token: String, chatId: String, memberId: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/members/$memberId").addHeader("Authorization", "Bearer $token").delete().build())

    suspend fun transferGroupOwnership(token: String, chatId: String, memberId: String): Result<Unit> =
        sendUnit(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/chats/$chatId/members/$memberId/ownership")
                .addHeader("Authorization", "Bearer $token")
                .put(ByteArray(0).toRequestBody(null))
                .build()
        )

    suspend fun renameGroup(token: String, chatId: String, newName: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/name").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(CreateChatRequest.serializer(), CreateChatRequest(emptyList(), true, newName)))).build())

    suspend fun getGroupMembers(token: String, chatId: String): Result<List<GroupMemberDto>> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/members").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(GroupMemberDto.serializer()))

    suspend fun getSenderKeyDistributionStatus(
        token: String,
        chatId: String,
        epoch: Long? = null,
        currentDeviceId: Int? = null
    ): Result<SenderKeyDistributionStatusDto> {
        val query = buildList {
            epoch?.let { add("epoch=$it") }
            currentDeviceId?.let { add("currentDeviceId=$it") }
        }.joinToString("&").let { if (it.isBlank()) "" else "?$it" }
        return send(
            Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/sender-key-distributions$query").addHeader("Authorization", "Bearer $token").get().build(),
            SenderKeyDistributionStatusDto.serializer()
        )
    }

    suspend fun getDevices(token: String, userId: String, currentDeviceId: Int? = null): Result<List<DeviceInfoDto>> {
        val suffix = currentDeviceId?.let { "?currentDeviceId=$it" }.orEmpty()
        return send(
            Request.Builder().url("${ApiConfig.BASE_URL}/api/keys/$userId/devices$suffix").addHeader("Authorization", "Bearer $token").get().build(),
            ListSerializer(DeviceInfoDto.serializer())
        )
    }

    /** Signal 密钥包上传：走 executeWithRefresh，冷启动 JWT 过期时先 refresh */
    suspend fun uploadKeys(token: String, request: UploadKeysRequest): Result<Unit> =
        sendUnit(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/keys/upload")
                .addHeader("Authorization", "Bearer $token")
                .post(jsonBody(json.encodeToString(UploadKeysRequest.serializer(), request)))
                .build()
        )

    suspend fun getPreKeyBundle(token: String, userId: String): Result<PreKeyBundleDto> =
        send(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/keys/$userId/prekey-bundle")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build(),
            PreKeyBundleDto.serializer()
        )

    suspend fun getDevicePreKeyBundle(token: String, userId: String, deviceId: Int): Result<DevicePreKeyBundleDto> =
        send(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/keys/$userId/devices/$deviceId/prekey-bundle")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build(),
            DevicePreKeyBundleDto.serializer()
        )

    suspend fun getDevicePreKeyBundles(token: String, userId: String): Result<List<DevicePreKeyBundleDto>> =
        send(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/keys/$userId/prekey-bundles")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build(),
            ListSerializer(DevicePreKeyBundleDto.serializer())
        )

    suspend fun removeMyDevice(token: String, deviceId: Int): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/keys/devices/$deviceId").addHeader("Authorization", "Bearer $token").delete().build())

    suspend fun renameMyDevice(token: String, deviceId: Int, deviceName: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/keys/devices/$deviceId/name").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateDeviceNameRequest.serializer(), UpdateDeviceNameRequest(deviceName)))).build())

    suspend fun confirmMyDevice(token: String, deviceId: Int, approverDeviceId: Int, signature: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/keys/devices/$deviceId/confirm").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(ConfirmDeviceRequest.serializer(), ConfirmDeviceRequest(approverDeviceId, signature)))).build())

    suspend fun updateMemberRole(token: String, chatId: String, memberId: String, role: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/members/$memberId/role").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateMemberRoleRequest.serializer(), UpdateMemberRoleRequest(role)))).build())

    suspend fun updateGroupNickname(token: String, chatId: String, nickname: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/members/me/nickname").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateGroupNicknameRequest.serializer(), UpdateGroupNicknameRequest(nickname)))).build())

    suspend fun updateMemberTitle(token: String, chatId: String, memberId: String, title: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/members/$memberId/title").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateMemberTitleRequest.serializer(), UpdateMemberTitleRequest(title)))).build())

    suspend fun updateMemberMute(token: String, chatId: String, memberId: String, mutedUntil: Long): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/members/$memberId/mute").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateMemberMuteRequest.serializer(), UpdateMemberMuteRequest(mutedUntil)))).build())

    /** 0.99：全员静音（除群主/管理员；mutedUntil=0 解除全员）。 */
    suspend fun muteAllMembers(token: String, chatId: String, mutedUntil: Long): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/mute-all").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(UpdateMemberMuteRequest.serializer(), UpdateMemberMuteRequest(mutedUntil)))).build())

    suspend fun updateGroupAnnouncement(token: String, chatId: String, announcement: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/announcement").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateGroupAnnouncementRequest.serializer(), UpdateGroupAnnouncementRequest(announcement)))).build())

    suspend fun getOrCreateGroupInvite(token: String, chatId: String, rotate: Boolean = false, expiresInSeconds: Long = 7L * 24L * 60L * 60L, maxUses: Int = 100): Result<GroupInviteResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/invite-token").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(CreateGroupInviteRequest.serializer(), CreateGroupInviteRequest(rotate, expiresInSeconds, maxUses)))).build(), GroupInviteResponse.serializer())

    suspend fun uploadGroupAvatar(token: String, chatId: String, base64Data: String): Result<String> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/avatar").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(UploadAvatarRequest.serializer(), UploadAvatarRequest(base64Data)))).build(), AvatarResponse.serializer()).map { it.avatarUrl }

    suspend fun getGroupAudit(token: String, chatId: String, limit: Int = 50, offset: Int = 0): Result<List<GroupAuditLogDto>> =
        // 8.64：支持 offset 分页（历史审计翻页）
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/audit?limit=${limit.coerceIn(1, 100)}&offset=${offset.coerceAtLeast(0)}").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(GroupAuditLogDto.serializer()))

    suspend fun joinGroupByInvite(token: String, inviteToken: String): Result<ChatDto> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/join-by-invite").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(JoinGroupInviteRequest.serializer(), JoinGroupInviteRequest(inviteToken)))).build(), ChatDto.serializer())

    suspend fun deleteChat(token: String, chatId: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId").addHeader("Authorization", "Bearer $token").delete().build())

    suspend fun toggleStarMessage(token: String, messageId: String): Result<StarMessageResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/messages/$messageId/star").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), StarMessageResponse.serializer())

    suspend fun getPinnedMessages(token: String, chatId: String): Result<PinnedMessagesListResponse> =
        send(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/chats/$chatId/pins")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build(),
            PinnedMessagesListResponse.serializer()
        )

    suspend fun togglePinnedMessage(token: String, chatId: String, messageId: String): Result<TogglePinResponse> =
        send(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/chats/$chatId/messages/$messageId/pin")
                .addHeader("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody(null))
                .build(),
            TogglePinResponse.serializer()
        )

    suspend fun getStarredMessages(token: String, chatId: String? = null): Result<List<StarredMessageRefDto>> {
        val suffix = chatId?.takeIf { it.isNotBlank() }?.let { "?chatId=${java.net.URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        return send(Request.Builder().url("${ApiConfig.BASE_URL}/api/messages/starred$suffix").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(StarredMessageRefDto.serializer()))
    }

    suspend fun getUsers(token: String, limit: Int = 30, offset: Int = 0): Result<List<UserDto>> =
        send(
            Request.Builder()
                .url(
                    "${ApiConfig.BASE_URL}/api/users" +
                        "?limit=${limit.coerceIn(1, 100)}" +
                        "&offset=${offset.coerceAtLeast(0)}"
                )
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build(),
            ListSerializer(UserDto.serializer())
        )

    /**
     * 翻页拉取全部可搜索用户（群加人候选等场景）。服务端每页最多 100 人，
     * 客户端按页循环直到返回空或达到 [maxUsers] 上限，避免群成员列表只显示前 30 人。
     */
    suspend fun getAllSearchableUsers(
        token: String,
        pageSize: Int = 100,
        maxUsers: Int = 1000
    ): Result<List<UserDto>> {
        if (token.isBlank()) return Result.failure(IllegalArgumentException("token_missing"))
        val safePageSize = pageSize.coerceIn(1, 100)
        val safeMax = maxUsers.coerceAtLeast(1)
        val collected = mutableListOf<UserDto>()
        var offset = 0
        while (offset < safeMax) {
            val page = getUsers(token, limit = safePageSize, offset = offset)
                .getOrElse { return Result.failure(it) }
            if (page.isEmpty()) break
            collected += page
            if (collected.size >= safeMax) break
            offset += safePageSize
        }
        return Result.success(collected.take(safeMax))
    }

    suspend fun getUser(token: String, userId: String): Result<UserDto> =
        send(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/users/${java.net.URLEncoder.encode(userId, Charsets.UTF_8.name())}")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build(),
            UserDto.serializer()
        )

    suspend fun getNearbyLocationStatus(token: String): Result<NearbyLocationStatusResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/nearby-location").addHeader("Authorization", "Bearer $token").get().build(), NearbyLocationStatusResponse.serializer())

    suspend fun updateNearbyLocation(token: String, latitude: Double, longitude: Double): Result<NearbyLocationStatusResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/nearby-location").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateNearbyLocationRequest.serializer(), UpdateNearbyLocationRequest(latitude, longitude)))).build(), NearbyLocationStatusResponse.serializer())

    suspend fun stopNearbyLocationSharing(token: String): Result<NearbyLocationStatusResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/nearby-location").addHeader("Authorization", "Bearer $token").delete().build(), NearbyLocationStatusResponse.serializer())

    suspend fun getNearbyUsers(token: String, radiusKm: Double = 10.0, limit: Int = 50): Result<List<NearbyUserResponse>> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/nearby?radiusKm=${radiusKm.coerceIn(0.5, 20.0)}&limit=${limit.coerceIn(1, 100)}").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(NearbyUserResponse.serializer()))

    suspend fun searchUsers(token: String, query: String, limit: Int = 30): Result<List<UserDto>> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=$limit").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(UserDto.serializer()))

    suspend fun getCurrentUser(token: String): Result<UserDto> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/me").addHeader("Authorization", "Bearer $token").get().build(), UserDto.serializer())

    /** 获取当前用户公开信息（含用户名） */
    suspend fun getCurrentUserPublic(token: String): Result<CurrentUserPublicResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/me/public").addHeader("Authorization", "Bearer $token").get().build(), CurrentUserPublicResponse.serializer())

    /** 获取公开个人主页信息（无需认证） */
    suspend fun getPublicProfile(username: String): Result<PublicProfileResponse> {
        val url = "${ApiConfig.BASE_URL}/api/public/profile/${java.net.URLEncoder.encode(username, "UTF-8")}"
        return send(Request.Builder().url(url).get().build(), PublicProfileResponse.serializer())
    }

    /** 设置用户名 */
    suspend fun setUsername(token: String, username: String): Result<SetUsernameResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/me/username").addHeader("Authorization", "Bearer $token")
            .put(jsonBody(json.encodeToString(SetUsernameRequest.serializer(), SetUsernameRequest(username)))).build(),
            SetUsernameResponse.serializer())

    /** 清除用户名 */
    suspend fun clearUsername(token: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/me/username").addHeader("Authorization", "Bearer $token").delete().build())

    /** 高级搜索 */
    suspend fun getPrivacy(token: String): Result<UserPrivacyDto> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/privacy").addHeader("Authorization", "Bearer $token").get().build(), UserPrivacyDto.serializer())

    suspend fun updatePrivacy(
        token: String,
        showOnline: Boolean? = null,
        showStatus: Boolean? = null,
        searchable: Boolean? = null,
        defaultPostVisibility: String? = null,
        onlineVisibility: String? = null
    ): Result<UserPrivacyDto> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/privacy").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdatePrivacyRequest.serializer(), UpdatePrivacyRequest(showOnline, showStatus, searchable, defaultPostVisibility, onlineVisibility)))).build(), UserPrivacyDto.serializer())

    suspend fun getPublicUpdates(officialBaseUrl: String = BuildConfig.API_BASE_URL): Result<PublicUpdatesDto> =
        send(
            Request.Builder().url("${officialBaseUrl.trimEnd('/')}/api/public/updates").get().build(),
            PublicUpdatesDto.serializer()
        )

    suspend fun getNotificationSettings(token: String): Result<NotificationSettingsResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/notification-settings").addHeader("Authorization", "Bearer $token").get().build(), NotificationSettingsResponse.serializer())

    suspend fun updateNotificationSettings(token: String, request: NotificationSettingsRequest): Result<NotificationSettingsResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/notification-settings").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(NotificationSettingsRequest.serializer(), request))).build(), NotificationSettingsResponse.serializer())

    suspend fun registerPushToken(
        token: String,
        deviceId: String,
        pushToken: String,
        timezoneOffsetMinutes: Int
    ): Result<Unit> = sendUnit(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/users/push-tokens")
            .addHeader("Authorization", "Bearer $token")
            .post(jsonBody(json.encodeToString(
                RegisterPushTokenRequest.serializer(),
                RegisterPushTokenRequest(deviceId, pushToken, timezoneOffsetMinutes = timezoneOffsetMinutes)
            )))
            .build()
    )

    suspend fun removePushToken(token: String, deviceId: String): Result<Unit> = sendUnit(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/users/push-tokens")
            .addHeader("Authorization", "Bearer $token")
            .delete(jsonBody(json.encodeToString(RemovePushTokenRequest.serializer(), RemovePushTokenRequest(deviceId))))
            .build()
    )

    /** WebRTC 信令 REST：走 executeWithRefresh，长通话 JWT 过期后仍可挂断/补发 */
    suspend fun sendSignaling(
        token: String,
        toUserId: String,
        type: String,
        payload: String,
        callId: String = "",
        groupId: String = "",
        groupMemberIds: List<String> = emptyList(),
        groupInvite: Boolean = false
    ): Result<Unit> = sendUnit(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/signaling/send")
            .addHeader("Authorization", "Bearer $token")
            .post(
                jsonBody(
                    json.encodeToString(
                        SignalingSendRequest.serializer(),
                        SignalingSendRequest(
                            toUserId = toUserId,
                            type = type,
                            payload = payload,
                            callId = callId,
                            groupId = groupId,
                            groupMemberIds = groupMemberIds,
                            groupInvite = groupInvite
                        )
                    )
                )
            )
            .build()
    )

    suspend fun hangUpCall(
        token: String,
        toUserId: String,
        callId: String = "",
        groupId: String = "",
        groupMemberIds: List<String> = emptyList()
    ): Result<Unit> = sendUnit(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/signaling/hangup")
            .addHeader("Authorization", "Bearer $token")
            .post(
                jsonBody(
                    json.encodeToString(
                        SignalingSendRequest.serializer(),
                        SignalingSendRequest(
                            toUserId = toUserId,
                            type = "hang-up",
                            payload = "",
                            callId = callId,
                            groupId = groupId,
                            groupMemberIds = groupMemberIds
                        )
                    )
                )
            )
            .build()
    )

    /** 轮询待处理信令（含 offersOnly 冷启动）；走 executeWithRefresh */
    suspend fun getPendingSignaling(token: String, offersOnly: Boolean = false): Result<List<SignalMessageDto>> {
        val query = if (offersOnly) "?offersOnly=true" else ""
        return send(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/signaling/pending$query")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build(),
            ListSerializer(SignalMessageDto.serializer())
        )
    }

    /** TURN/STUN 配置；走 executeWithRefresh，避免长通话 JWT 过期后 ICE 刷新失败 */
    suspend fun getIceConfig(token: String): Result<IceConfigDto> =
        send(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/calls/ice-config")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build(),
            IceConfigDto.serializer()
        )

    suspend fun blockUser(token: String, userId: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/block/$userId").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build())

    suspend fun unblockUser(token: String, userId: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/block/$userId").addHeader("Authorization", "Bearer $token").delete().build())

    suspend fun getBlockedUsers(token: String): Result<List<String>> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/blocks").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(String.serializer()))

    suspend fun getBlockedUserDetails(token: String): Result<List<UserDto>> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/blocks/details").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(UserDto.serializer()))

    // ─── 发现页 / 动态 ─────────────────────────

    suspend fun getPosts(
        token: String,
        limit: Int = 40,
        before: Long? = null,
        beforeId: String? = null,
        authorId: String? = null
    ): Result<List<PostDto>> {
        val params = buildList {
            add("limit=$limit")
            before?.let { add("before=$it") }
            beforeId?.takeIf { before != null }?.let {
                add("beforeId=${java.net.URLEncoder.encode(it, Charsets.UTF_8.name())}")
            }
            authorId?.let {
                add("authorId=${java.net.URLEncoder.encode(it, Charsets.UTF_8.name())}")
            }
        }.joinToString("&")
        return send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts?$params").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(PostDto.serializer()))
    }

    suspend fun createPost(token: String, content: String, imageUrls: List<String>, visibility: String? = null): Result<PostDto> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(CreatePostRequest.serializer(), CreatePostRequest(content, imageUrls, visibility, visibility == null)))).build(), PostDto.serializer())

    suspend fun getPost(token: String, postId: String): Result<PostDto> =
        send(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build(),
            PostDto.serializer()
        )

    suspend fun editPost(token: String, postId: String, content: String, visibility: String? = null): Result<PostDto> =
        // 8.58：postId 统一 URL 编码（与 getPost 一致，防保留字符路由错乱）
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(EditPostRequest.serializer(), EditPostRequest(content, visibility)))).build(), PostDto.serializer())

    suspend fun deletePost(token: String, postId: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}").addHeader("Authorization", "Bearer $token").delete().build())

    suspend fun likePost(token: String, postId: String): Result<PostDto> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/like").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), PostDto.serializer())

    suspend fun unlikePost(token: String, postId: String): Result<PostDto> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/like").addHeader("Authorization", "Bearer $token").delete().build(), PostDto.serializer())

    suspend fun getPostComments(
        token: String,
        postId: String,
        limit: Int = 50,
        before: Long? = null,
        beforeId: String? = null
    ): Result<List<PostCommentDto>> {
        val params = buildList {
            add("limit=${limit.coerceIn(1, 100)}")
            before?.let { add("before=$it") }
            beforeId?.takeIf { before != null }?.let {
                add("beforeId=${java.net.URLEncoder.encode(it, Charsets.UTF_8.name())}")
            }
        }.joinToString("&")
        return send(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/comments?$params")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build(),
            ListSerializer(PostCommentDto.serializer())
        )
    }

    suspend fun createPostComment(token: String, postId: String, content: String, replyToId: String? = null): Result<PostCommentDto> =
        // 8.58：postId 统一 URL 编码（与 getPost 一致，防保留字符路由错乱）
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/comments").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(CreateCommentRequest.serializer(), CreateCommentRequest(content, replyToId)))).build(), PostCommentDto.serializer())

    suspend fun editPostComment(token: String, postId: String, commentId: String, content: String): Result<PostCommentDto> =
        send(
            Request.Builder()
                .url(
                    "${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}" +
                        "/comments/${java.net.URLEncoder.encode(commentId, Charsets.UTF_8.name())}"
                )
                .addHeader("Authorization", "Bearer $token")
                .put(jsonBody(json.encodeToString(UpdateCommentRequest.serializer(), UpdateCommentRequest(content))))
                .build(),
            PostCommentDto.serializer()
        )

    /** 1.93：动态点赞者列表。 */
    suspend fun getPostLikers(token: String, postId: String, limit: Int = 50): Result<PostLikersResponse> =
        send(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/likers?limit=${limit.coerceIn(1, 100)}")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build(),
            PostLikersResponse.serializer()
        )

    /** 1.00：删除自己的评论。 */
    suspend fun deleteComment(token: String, postId: String, commentId: String): Result<Unit> =
        sendUnit(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/comments/${java.net.URLEncoder.encode(commentId, Charsets.UTF_8.name())}")
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()
        )

    /** 1.52：点赞评论。 */
    suspend fun likeComment(token: String, postId: String, commentId: String): Result<CommentLikeResponse> =
        send(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/comments/${java.net.URLEncoder.encode(commentId, Charsets.UTF_8.name())}/like")
                .addHeader("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody(null))
                .build(),
            CommentLikeResponse.serializer()
        )

    /** 1.52：取消点赞评论。 */
    suspend fun unlikeComment(token: String, postId: String, commentId: String): Result<CommentLikeResponse> =
        send(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/posts/${java.net.URLEncoder.encode(postId, Charsets.UTF_8.name())}/comments/${java.net.URLEncoder.encode(commentId, Charsets.UTF_8.name())}/like")
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build(),
            CommentLikeResponse.serializer()
        )

    suspend fun uploadPostImage(token: String, base64Data: String): Result<String> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts/images").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(UploadPostImageRequest.serializer(), UploadPostImageRequest(base64Data)))).build(), UploadPostImageResponse.serializer()).map { it.imageUrl }

    suspend fun discardPostImage(token: String, imageUrl: String): Result<Unit> {
        val filename = imageUrl.substringAfterLast('/').takeIf { it.matches(POST_IMAGE_FILENAME_REGEX) }
            ?: return Result.failure(IllegalArgumentException("invalid_post_image_url"))
        return sendUnit(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/posts/images/$filename")
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()
        )
    }

    // ─── 邮箱验证码 ──────────────────────────

    suspend fun sendVerificationCode(email: String, purpose: String = "register"): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/auth/send-code").post(jsonBody(json.encodeToString(SendCodeRequest.serializer(), SendCodeRequest(email, purpose)))).build())

    suspend fun registerWithCode(name: String, email: String, password: String, code: String): Result<AuthResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/auth/register-with-code").post(jsonBody(json.encodeToString(RegisterWithCodeRequest.serializer(), RegisterWithCodeRequest(name, email, password, code)))).build(), AuthResponse.serializer())

    suspend fun resetPassword(email: String, code: String, newPassword: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/auth/reset-password").post(jsonBody(json.encodeToString(ResetPasswordRequest.serializer(), ResetPasswordRequest(email, code, newPassword)))).build())

    // ─── 好友申请 ──────────────────────────

    suspend fun sendFriendRequest(token: String, toUserId: String, message: String = ""): Result<FriendRequestDto> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/requests").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(SendFriendRequestBody.serializer(), SendFriendRequestBody(toUserId, message)))).build(), FriendRequestDto.serializer())

    suspend fun getIncomingFriendRequests(token: String, status: String = "PENDING", limit: Int = 50): Result<List<FriendRequestDto>> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/requests/incoming?status=$status&limit=${limit.coerceIn(1, 100)}").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(FriendRequestDto.serializer()))

    suspend fun getOutgoingFriendRequests(token: String, status: String = "PENDING", limit: Int = 50): Result<List<FriendRequestDto>> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/requests/outgoing?status=$status&limit=${limit.coerceIn(1, 100)}").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(FriendRequestDto.serializer()))

    suspend fun acceptFriendRequest(token: String, requestId: String): Result<FriendRequestDto> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/requests/$requestId/accept").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), FriendRequestDto.serializer())

    suspend fun rejectFriendRequest(token: String, requestId: String): Result<FriendRequestDto> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/requests/$requestId/reject").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), FriendRequestDto.serializer())

    suspend fun cancelFriendRequest(token: String, requestId: String): Result<FriendRequestDto> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/requests/$requestId/cancel").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), FriendRequestDto.serializer())

    suspend fun getFriends(token: String): Result<List<UserDto>> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(UserDto.serializer()))

    suspend fun removeFriend(token: String, friendId: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/friends/$friendId").addHeader("Authorization", "Bearer $token").delete().build())

    // ─── 9.3xx：群邀请同意流程 ─────────────────

    suspend fun getGroupInvitations(token: String): Result<List<GroupInvitationDto>> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/group-invitations").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(GroupInvitationDto.serializer()))

    suspend fun acceptGroupInvitation(token: String, inviteId: String): Result<GroupInviteAcceptResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/group-invitations/$inviteId/accept").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), GroupInviteAcceptResponse.serializer())

    suspend fun declineGroupInvitation(token: String, inviteId: String): Result<GroupInviteAcceptResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/group-invitations/$inviteId/decline").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), GroupInviteAcceptResponse.serializer())

    suspend fun cancelGroupInvitation(token: String, inviteId: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/group-invitations/$inviteId").addHeader("Authorization", "Bearer $token").delete().build())

    suspend fun getChatGroupInvitations(token: String, chatId: String): Result<List<GroupInvitationDto>> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/invitations").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(GroupInvitationDto.serializer()))

    // ─── 会话文件夹云同步 ─────────────────

    suspend fun getChatFolders(token: String): Result<ChatFoldersSyncResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chat-folders").addHeader("Authorization", "Bearer $token").get().build(), ChatFoldersSyncResponse.serializer())

    suspend fun putChatFolders(token: String, folders: List<ChatFolderDto>): Result<ChatFoldersSyncResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chat-folders").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(ChatFoldersSyncRequest.serializer(), ChatFoldersSyncRequest(folders)))).build(), ChatFoldersSyncResponse.serializer())

    // ─── 客户端外观/列表偏好云同步 ────────────

    suspend fun getClientPrefs(token: String): Result<ClientPrefsDto> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/client-prefs").addHeader("Authorization", "Bearer $token").get().build(), ClientPrefsDto.serializer())

    suspend fun putClientPrefs(token: String, request: ClientPrefsUpdateRequest): Result<ClientPrefsDto> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/client-prefs").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(ClientPrefsUpdateRequest.serializer(), request))).build(), ClientPrefsDto.serializer())

    // ─── 头像上传 + 修改资料 ─────────────────

    suspend fun changePassword(token: String, oldPassword: String, newPassword: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/change-password").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(ChangePasswordRequest.serializer(), ChangePasswordRequest(oldPassword, newPassword)))).build())

    suspend fun deleteAccount(token: String, password: String): Result<DeleteAccountResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/me").addHeader("Authorization", "Bearer $token").delete(jsonBody(json.encodeToString(DeleteAccountRequest.serializer(), DeleteAccountRequest(password)))).build(), DeleteAccountResponse.serializer())

    suspend fun createReport(
        token: String,
        targetType: String,
        targetId: String,
        chatId: String? = null,
        messageId: String? = null,
        reason: String,
        description: String? = null
    ): Result<ReportResponse> =
        send(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/reports")
                .addHeader("Authorization", "Bearer $token")
                .post(jsonBody(json.encodeToString(CreateReportRequest.serializer(), CreateReportRequest(targetType, targetId, chatId, messageId, reason, description))))
                .build(),
            ReportResponse.serializer()
        )

    suspend fun getMyReports(token: String, limit: Int = 50): Result<List<ReportResponse>> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/reports/mine?limit=${limit.coerceIn(1, 100)}").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(ReportResponse.serializer()))

    suspend fun getAdminReports(token: String, status: String? = null, limit: Int = 100): Result<List<ReportResponse>> {
        val statusPart = status?.takeIf { it.isNotBlank() && it != "ALL" }?.let { "&status=$it" } ?: ""
        return send(Request.Builder().url("${ApiConfig.BASE_URL}/api/moderator/reports?limit=${limit.coerceIn(1, 200)}$statusPart").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(ReportResponse.serializer()))
    }

    suspend fun updateReportStatus(token: String, reportId: String, status: String, resolutionNote: String? = null): Result<ReportResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/moderator/reports/$reportId/status").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateReportStatusRequest.serializer(), UpdateReportStatusRequest(status, resolutionNote)))).build(), ReportResponse.serializer())

    suspend fun applyReportAction(token: String, reportId: String, action: String, resolutionNote: String? = null): Result<ReportResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/moderator/reports/$reportId/action").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(ApplyReportActionRequest.serializer(), ApplyReportActionRequest(action, resolutionNote)))).build(), ReportResponse.serializer())

    suspend fun getModerationRules(token: String): Result<List<ModerationRuleResponse>> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/admin/moderation/rules").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(ModerationRuleResponse.serializer()))

    suspend fun updateModerationRule(token: String, ruleId: String, request: UpdateModerationRuleRequest): Result<ModerationRuleResponse> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/admin/moderation/rules/$ruleId").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateModerationRuleRequest.serializer(), request))).build(), ModerationRuleResponse.serializer())

    suspend fun getRiskEvents(token: String, needsReview: Boolean? = null, limit: Int = 100): Result<List<RiskEventResponse>> {
        val reviewPart = needsReview?.let { "&needsReview=$it" } ?: ""
        return send(Request.Builder().url("${ApiConfig.BASE_URL}/api/admin/moderation/events?limit=${limit.coerceIn(1, 200)}$reviewPart").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(RiskEventResponse.serializer()))
    }

    suspend fun acknowledgeRiskEvent(token: String, eventId: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/admin/moderation/events/$eventId/ack").addHeader("Authorization", "Bearer $token").post("".toRequestBody(JSON_MEDIA)).build())

    suspend fun uploadAvatar(token: String, base64Data: String): Result<String> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/avatar").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(UploadAvatarRequest.serializer(), UploadAvatarRequest(base64Data)))).build(), AvatarResponse.serializer()).map { it.avatarUrl }

    suspend fun removeAvatar(token: String): Result<Unit> =
        sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/avatar").addHeader("Authorization", "Bearer $token").delete().build())

    suspend fun updateProfile(token: String, name: String? = null, status: String? = null): Result<UserDto> =
        send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/profile").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateProfileRequest.serializer(), UpdateProfileRequest(name = name, status = status)))).build(), UserDto.serializer())

    suspend fun createGroupPoll(
        token: String,
        chatId: String,
        question: String,
        options: List<String>,
        multi: Boolean = false,
        anonymous: Boolean = false,
        closesAt: Long? = null
    ): Result<String> {
        val body = buildString {
            append("{")
            append("\"question\":"); append(org.json.JSONObject.quote(question)); append(',')
            append("\"options\":[")
            append(options.joinToString(",") { org.json.JSONObject.quote(it) })
            append("],")
            append("\"multi\":"); append(multi); append(',')
            append("\"anonymous\":"); append(anonymous)
            if (closesAt != null) { append(",\"closesAt\":"); append(closesAt) }
            append("}")
        }
        val req = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/chats/$chatId/polls")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return executeForText(req, "poll_create")
    }

    suspend fun voteGroupPoll(token: String, pollId: String, optionIndexes: List<Int>): Result<String> {
        val body = """{"optionIndexes":[${optionIndexes.joinToString(",")}]}"""
        val req = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/polls/$pollId/vote")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return executeForText(req, "poll_vote")
    }

    suspend fun listBots(token: String): Result<String> {
        val req = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/bots")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return executeForText(req, "bots")
    }

    suspend fun createBot(token: String, name: String, username: String, description: String? = null): Result<String> {
        val o = org.json.JSONObject()
        o.put("name", name)
        o.put("username", username)
        if (description != null) o.put("description", description)
        val req = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/bots")
            .header("Authorization", "Bearer $token")
            .post(o.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return executeForText(req, "bot_create")
    }


    suspend fun getGroupPoll(token: String, pollId: String): Result<String> {
        val req = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/polls/$pollId")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return executeForText(req, "poll_get")
    }

    suspend fun setBotWebhook(token: String, botId: String, url: String?): Result<String> {
        val payload = if (url == null) """{"url":null}""" else org.json.JSONObject().put("url", url).toString()
        val req = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/bots/$botId/webhook")
            .header("Authorization", "Bearer $token")
            .put(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return executeForText(req, "bot_webhook")
    }

    suspend fun regenerateBotToken(token: String, botId: String): Result<String> {
        val req = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/bots/$botId/token")
            .header("Authorization", "Bearer $token")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        return executeForText(req, "bot_token")
    }


    suspend fun listChatBotCommands(token: String, chatId: String): Result<String> {
        val req = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/chats/$chatId/bot-commands")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return executeForText(req, "bot_commands")
    }

    suspend fun postBotInbox(token: String, chatId: String, text: String, botId: String? = null): Result<String> {
        val payload = org.json.JSONObject().put("text", text).apply {
            if (!botId.isNullOrBlank()) put("botId", botId)
        }.toString()
        val req = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/chats/$chatId/bot-inbox")
            .header("Authorization", "Bearer $token")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return executeForText(req, "bot_inbox")
    }

    suspend fun openBotDirectChat(token: String, botId: String): Result<ChatDto> =
        send(
            Request.Builder()
                .url("${ApiConfig.BASE_URL}/api/bots/$botId/dm")
                .addHeader("Authorization", "Bearer $token")
                .post(jsonBody("{}"))
                .build(),
            ChatDto.serializer()
        )

    suspend fun inviteBotToChat(token: String, chatId: String, botId: String): Result<String> {
        val payload = org.json.JSONObject().put("botId", botId).toString()
        val req = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/chats/$chatId/bots")
            .header("Authorization", "Bearer $token")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return executeForText(req, "bot_invite")
    }

    suspend fun postBotCallback(
        token: String,
        chatId: String,
        messageId: String,
        botUserId: String,
        callbackData: String
    ): Result<Boolean> {
        val payload = org.json.JSONObject()
            .put("messageId", messageId)
            .put("botUserId", botUserId)
            .put("callbackData", callbackData)
            .toString()
        val req = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/chats/$chatId/bot-callback")
            .header("Authorization", "Bearer $token")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return executeForText(req, "bot_callback").map { true }
    }

    private suspend fun <T> runIoCatching(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun deleteBot(token: String, botId: String): Result<String> {
        val req = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/bots/$botId")
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        return executeForText(req, "bot_delete")
    }

    suspend fun setBotEnabled(token: String, botId: String, enabled: Boolean): Result<String> {
        val payload = org.json.JSONObject().put("enabled", enabled).toString()
        val req = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/bots/$botId/enabled")
            .header("Authorization", "Bearer $token")
            .put(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return executeForText(req, "bot_enabled")
    }

    /** 活跃公告（含本用户 acked 状态），返回 JSON 字符串由调用方解析。 */
    suspend fun getActiveAnnouncements(token: String): Result<String> {
        val req = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/announcements/active")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return executeForText(req, "announcements_active")
    }

    /** 公告已读确认。 */
    suspend fun ackAnnouncement(token: String, announcementId: String): Result<String> {
        val req = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/announcements/$announcementId/ack")
            .header("Authorization", "Bearer $token")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return executeForText(req, "announcement_ack")
    }

    /** 推送 HMAC 校验密钥（经认证通道下发；返回 JSON 字符串由调用方解析，key 为 null 表示未配置）。 */
    suspend fun getPushVerifyKey(token: String): Result<String> {
        val req = Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/push/verify-key")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return executeForText(req, "push_verify_key")
    }
}
