package com.maodouchat.server.service

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.repository.AiRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Server AI is **content moderation only** (posts/comments). Chat inference
 * (rewrite/summarize/translate/…) lives on the client with a user-configured model.
 */
interface AiGateway {
    val model: String

    suspend fun classifyContent(source: String, text: String): AiGatewayResult<String> =
        AiGatewayResult.Success(
            """{"verdict":"ALLOW","category":"ok","reason":""}""",
            model
        )

    fun checkBudget(userId: String, estimatedTokens: Long): BudgetResult = BudgetResult.Allowed
}

sealed interface AiGatewayResult<out T> {
    data class Success<T>(
        val value: T,
        val model: String,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null
    ) : AiGatewayResult<T>
    data object NotConfigured : AiGatewayResult<Nothing>
    data class UpstreamError(
        val statusCode: Int,
        val message: String?,
        val retryAfterSeconds: Long? = null
    ) : AiGatewayResult<Nothing>
    data class InvalidResponse(val message: String) : AiGatewayResult<Nothing>
}

sealed interface BudgetResult {
    data object Allowed : BudgetResult
    data class Exceeded(
        val usedTokens: Long,
        val budgetTokens: Long,
        val retryAfterSeconds: Long
    ) : BudgetResult
}

class AiGatewayService(
    private val client: HttpClient = defaultClient(),
    private val apiKeyProvider: () -> String = { ServerConfig.openAiApiKey },
    private val baseUrlProvider: () -> String = { ServerConfig.openAiBaseUrl },
    private val modelProvider: () -> String = { ServerConfig.openAiModel },
    private val auditRepository: AiRepository = AiRepository()
) : AiGateway {

    override val model: String
        get() = modelProvider()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override suspend fun classifyContent(source: String, text: String): AiGatewayResult<String> {
        val bounded = text.trim().take(AiContentModerationPolicy.MAX_INPUT_CHARS)
        if (bounded.isEmpty()) {
            return AiGatewayResult.Success(
                """{"verdict":"ALLOW","category":"ok","reason":""}""",
                model
            )
        }
        return createResponse(
            developerMessage = AiContentModerationPolicy.developerPrompt(),
            userMessage = "source=${source.take(20)}\n$bounded"
        )
    }

    private suspend fun createResponse(
        developerMessage: String,
        userMessage: String
    ): AiGatewayResult<String> {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) return AiGatewayResult.NotConfigured
        val input = listOf(
            OpenAiInputMessage(role = "developer", content = JsonPrimitive(developerMessage)),
            OpenAiInputMessage(role = "user", content = JsonPrimitive(userMessage))
        )
        val retryEnabled = RuntimeConfigService.isAiRetryEnabled()
        val maxAttempts = if (retryEnabled) MAX_RETRIES + 1 else 1
        var last: AiGatewayResult<String> = AiGatewayResult.UpstreamError(0, "no attempt")
        for (attempt in 0 until maxAttempts) {
            val result = performRequest(modelProvider(), input, MAX_OUTPUT_TOKENS)
            last = result
            when {
                result is AiGatewayResult.Success -> return result
                result is AiGatewayResult.UpstreamError && retryEnabled &&
                    isTransient(result.statusCode, result.message) && attempt < maxAttempts - 1 -> {
                    delay(BACKOFF_MS.getOrElse(attempt) { BACKOFF_MS.last() })
                }
                else -> return result
            }
        }
        return last
    }

    private val llmSemaphore = java.util.concurrent.Semaphore(LLM_MAX_CONCURRENCY)

    private suspend fun performRequest(
        model: String,
        input: List<OpenAiInputMessage>,
        maxOutputTokens: Int
    ): AiGatewayResult<String> {
        if (!withContext(kotlinx.coroutines.Dispatchers.IO) {
                llmSemaphore.tryAcquire(LLM_ACQUIRE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        ) {
            return AiGatewayResult.UpstreamError(429, "AI 并发已满，请稍后再试")
        }
        return try {
            val apiKey = apiKeyProvider().trim()
            if (apiKey.isBlank()) return AiGatewayResult.NotConfigured
            val response = try {
                client.post("${baseUrlProvider().trimEnd('/')}/chat/completions") {
                    bearerAuth(apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(buildChatRequest(model, input, maxOutputTokens))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return AiGatewayResult.UpstreamError(0, e.message)
            }
            val body = response.bodyAsTextSafe()
            if (body == null) {
                return AiGatewayResult.UpstreamError(0, "response body read failed")
            }
            if (response.status.value !in 200..299) {
                return AiGatewayResult.UpstreamError(
                    response.status.value,
                    extractErrorMessage(body) ?: "HTTP ${response.status.value} empty body"
                )
            }
            val output = OpenAiCompatibleCodec.extractOutputText(body)
            val usage = OpenAiCompatibleCodec.extractUsage(body)
            if (output.isNullOrBlank()) {
                AiGatewayResult.InvalidResponse("AI 响应为空")
            } else {
                val result = AiGatewayResult.Success(
                    output.trim(),
                    model,
                    usage?.first ?: estimateInputTokens(input),
                    usage?.second ?: maxOf(1L, output.trim().length / 4L)
                )
                runCatching {
                    auditRepository.recordAudit(
                        userId = "moderation",
                        chatId = null,
                        feature = "MODERATE_CONTENT",
                        model = model,
                        status = "ok",
                        inputChars = estimateInputChars(input),
                        inputTokens = result.inputTokens,
                        outputTokens = result.outputTokens
                    )
                }
                result
            }
        } finally {
            llmSemaphore.release()
        }
    }

    override fun checkBudget(userId: String, estimatedTokens: Long): BudgetResult {
        val budget = RuntimeConfigService.aiDailyTokenBudgetPerUser()
        if (budget <= 0L) return BudgetResult.Allowed
        val est = estimatedTokens.coerceAtLeast(0L) + BUDGET_OUTPUT_ESTIMATE_TOKENS
        val now = System.currentTimeMillis()
        val monitor = budgetMonitorFor(userId)
        try {
            synchronized(monitor.lock) {
                val used = runCatching { auditRepository.sumTokensForUserToday(userId) }.getOrDefault(0L)
                val reserved = purgeExpiredReservations(userId, now)
                if (used + reserved + est <= budget) {
                    budgetReservations
                        .computeIfAbsent(userId) { mutableListOf() }
                        .add(Reservation(amount = est, expiresAt = now + AI_BUDGET_RESERVATION_LEASE_MS))
                    return BudgetResult.Allowed
                }
                return BudgetResult.Exceeded(usedTokens = used, budgetTokens = budget, retryAfterSeconds = secondsUntilMidnight())
            }
        } finally {
            releaseBudgetMonitor(userId, monitor)
        }
    }

    private fun buildChatRequest(
        model: String,
        input: List<OpenAiInputMessage>,
        maxOutputTokens: Int
    ): ChatCompletionsRequest = ChatCompletionsRequest(
        model = model,
        messages = input.map { msg ->
            ChatMessage(
                role = OpenAiCompatibleCodec.chatRole(msg.role),
                content = OpenAiCompatibleCodec.chatContent(msg.content)
            )
        },
        maxTokens = maxOutputTokens,
        stream = false
    )

    private fun isTransient(statusCode: Int, message: String?): Boolean {
        if (statusCode in TRANSIENT_STATUSES) return true
        if (statusCode == 0) {
            val msg = message?.lowercase() ?: return true
            return msg.contains("timeout") || msg.contains("connection") || msg.contains("reset")
        }
        return false
    }

    private fun estimateInputChars(input: List<OpenAiInputMessage>): Int {
        var chars = 0
        for (msg in input) {
            val content = msg.content
            if (content is JsonPrimitive && content.isString) chars += content.content.length
        }
        return chars
    }

    private fun estimateInputTokens(input: List<OpenAiInputMessage>): Long =
        maxOf(1L, estimateInputChars(input) / 4L)

    private fun extractErrorMessage(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return (root["error"] as? JsonObject)
            ?.get("message")
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
    }

    private suspend fun HttpResponse.bodyAsTextSafe(): String? = try {
        bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private data class Reservation(val amount: Long, val expiresAt: Long)
    private val AI_BUDGET_RESERVATION_LEASE_MS = 120_000L
    private val BUDGET_OUTPUT_ESTIMATE_TOKENS = 1_024L
    private val budgetReservations = ConcurrentHashMap<String, MutableList<Reservation>>()
    private class BudgetMonitor(val lock: Any = Any(), var users: Int = 0)
    private val budgetMonitors = ConcurrentHashMap<String, BudgetMonitor>()
    private val budgetMonitorSweepAt = java.util.concurrent.atomic.AtomicLong(0L)
    private val MAX_BUDGET_MONITORS = 100_000

    private fun budgetMonitorFor(userId: String): BudgetMonitor {
        maybeSweepBudgetMonitors()
        return budgetMonitors.compute(userId) { _, existing ->
            val monitor = existing ?: BudgetMonitor()
            monitor.users++
            monitor
        }!!
    }

    private fun releaseBudgetMonitor(userId: String, monitor: BudgetMonitor) {
        budgetMonitors.computeIfPresent(userId) { _, current ->
            if (current === monitor) {
                if (current.users > 1) {
                    current.users--
                    current
                } else if (budgetReservations[userId] == null) {
                    null
                } else {
                    current.users = 0
                    current
                }
            } else {
                current
            }
        }
    }

    private fun maybeSweepBudgetMonitors() {
        if (budgetMonitors.size < MAX_BUDGET_MONITORS) return
        val now = System.currentTimeMillis()
        val last = budgetMonitorSweepAt.get()
        if (now - last < 60_000L) return
        if (!budgetMonitorSweepAt.compareAndSet(last, now)) return
        budgetMonitors.keys.forEach { key ->
            budgetMonitors.computeIfPresent(key) { _, current ->
                if (current.users == 0 && budgetReservations[key] == null) null else current
            }
        }
    }

    private fun purgeExpiredReservations(userId: String, now: Long): Long {
        val list = budgetReservations[userId] ?: return 0L
        val iter = list.iterator()
        var sum = 0L
        while (iter.hasNext()) {
            val r = iter.next()
            if (r.expiresAt <= now) iter.remove() else sum += r.amount
        }
        if (list.isEmpty()) budgetReservations.remove(userId)
        return sum
    }

    private fun secondsUntilMidnight(zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = Instant.now()
        val nextMidnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant()
        return Duration.between(now, nextMidnight).seconds.coerceAtLeast(1L)
    }

    @Serializable
    private data class OpenAiInputMessage(val role: String, val content: JsonElement)

    @Serializable
    private data class ChatMessage(val role: String, val content: JsonElement)

    @Serializable
    private data class ChatCompletionsRequest(
        val model: String,
        val messages: List<ChatMessage>,
        @kotlinx.serialization.SerialName("max_tokens") val maxTokens: Int,
        val stream: Boolean
    )

    companion object {
        const val MAX_RETRIES = 2
        const val LLM_MAX_CONCURRENCY = 16
        const val LLM_ACQUIRE_TIMEOUT_MS = 5_000L
        val BACKOFF_MS: LongArray = longArrayOf(500L, 1500L)
        val TRANSIENT_STATUSES: Set<Int> = setOf(429, 500, 502, 503)
        const val MAX_OUTPUT_TOKENS = 180

        private fun defaultClient(): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                })
            }
            engine {
                requestTimeout = 120_000
            }
        }
    }
}
