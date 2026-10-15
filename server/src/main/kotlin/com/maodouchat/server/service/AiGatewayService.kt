package com.maodouchat.server.service

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.repository.AiRepository
import com.maodouchat.server.repository.sumTokensForUserToday
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json

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
    internal val client: HttpClient = defaultClient(),
    internal val apiKeyProvider: () -> String = { ServerConfig.openAiApiKey },
    internal val baseUrlProvider: () -> String = { ServerConfig.openAiBaseUrl },
    internal val modelProvider: () -> String = { ServerConfig.openAiModel },
    internal val auditRepository: AiRepository = AiRepository()
) : AiGateway {

    override val model: String
        get() = modelProvider()

    internal val json = Json {
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

    internal val llmSemaphore = java.util.concurrent.Semaphore(LLM_MAX_CONCURRENCY)

    internal val AI_BUDGET_RESERVATION_LEASE_MS = 120_000L
    internal val BUDGET_OUTPUT_ESTIMATE_TOKENS = 1_024L
    internal val budgetReservations = ConcurrentHashMap<String, MutableList<Reservation>>()
    internal val budgetMonitors = ConcurrentHashMap<String, BudgetMonitor>()
    internal val budgetMonitorSweepAt = java.util.concurrent.atomic.AtomicLong(0L)
    internal val MAX_BUDGET_MONITORS = 100_000

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
