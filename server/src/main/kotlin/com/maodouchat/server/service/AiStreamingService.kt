package com.maodouchat.server.service

import com.maodouchat.server.config.ServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicLong

/**
 * AI token 估算与重试工具（0.86：原 streaming client 实例方法从未被实例化，已删除，
 * 保留 companion 工具函数；真实 AI 调用由 AiGatewayService 负责）。
 */
object AiStreamingService {
        const val CHUNK_TIMEOUT_MS = 30_000L
        const val MAX_RETRIES = 2
        const val INITIAL_BACKOFF_MS = 1_000L
        val RETRYABLE_STATUSES = setOf(500, 502, 503)

        fun estimateTokens(text: String): Int {
            var cjkCount = 0
            var otherCount = 0
            for (ch in text) {
                if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF ||
                    ch.code in 0x20000..0x2A6DF || ch.code in 0x2A700..0x2B73F ||
                    ch.code in 0x2B740..0x2B81F || ch.code in 0x2B820..0x2CEAF ||
                    ch.code in 0xF900..0xFAFF || ch.code in 0x2F800..0x2FA1F ||
                    ch.code in 0x3040..0x309F || ch.code in 0x30A0..0x30FF ||
                    ch.code in 0xAC00..0xD7AF
                ) {
                    cjkCount++
                } else {
                    otherCount++
                }
            }
            return (cjkCount / 2) + (otherCount / 4) + 1
        }

        private fun defaultStreamClient(): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                requestTimeout = 120_000
                pipelining = true
            }
        }
    }

// ── Data classes ──

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
private data class StreamRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    @kotlinx.serialization.SerialName("max_tokens")
    val maxTokens: Int = 4096,
    val stream: Boolean = true
)

@Serializable
private data class CompletionResponse(
    val choices: List<CompletionChoice> = emptyList()
)

@Serializable
private data class CompletionChoice(
    val message: CompletionMessage? = null
)

@Serializable
private data class CompletionMessage(
    val role: String = "",
    val content: String = ""
)

data class StreamResult(
    val success: Boolean,
    val elapsedMs: Long = 0,
    val estimatedTokens: Int = 0,
    val error: String? = null
)

data class CompletionResult(
    val success: Boolean,
    val content: String = "",
    val elapsedMs: Long = 0,
    val estimatedTokens: Int = 0,
    val error: String? = null
)

class AiApiException(message: String) : RuntimeException(message)
