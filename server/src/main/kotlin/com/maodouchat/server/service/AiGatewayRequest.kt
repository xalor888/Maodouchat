package com.maodouchat.server.service

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import com.maodouchat.server.repository.recordAudit

@Serializable
internal data class OpenAiInputMessage(val role: String, val content: JsonElement)

internal suspend fun AiGatewayService.createResponse(
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
    val maxAttempts = if (retryEnabled) AiGatewayService.MAX_RETRIES + 1 else 1
    var last: AiGatewayResult<String> = AiGatewayResult.UpstreamError(0, "no attempt")
    for (attempt in 0 until maxAttempts) {
        val result = performRequest(modelProvider(), input, AiGatewayService.MAX_OUTPUT_TOKENS)
        last = result
        when {
            result is AiGatewayResult.Success -> return result
            result is AiGatewayResult.UpstreamError && retryEnabled &&
                isTransient(result.statusCode, result.message) && attempt < maxAttempts - 1 -> {
                delay(AiGatewayService.BACKOFF_MS.getOrElse(attempt) { AiGatewayService.BACKOFF_MS.last() })
            }
            else -> return result
        }
    }
    return last
}

internal suspend fun AiGatewayService.performRequest(
    model: String,
    input: List<OpenAiInputMessage>,
    maxOutputTokens: Int
): AiGatewayResult<String> {
    if (!withContext(Dispatchers.IO) {
            llmSemaphore.tryAcquire(AiGatewayService.LLM_ACQUIRE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
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

internal fun AiGatewayService.buildChatRequest(
    model: String,
    input: List<OpenAiInputMessage>,
    maxOutputTokens: Int
): ChatCompletionsRequest = ChatCompletionsRequest(
    model = model,
    messages = input.map { msg ->
        ChatCompletionsMessage(
            role = OpenAiCompatibleCodec.chatRole(msg.role),
            content = OpenAiCompatibleCodec.chatContent(msg.content)
        )
    },
    maxTokens = maxOutputTokens,
    stream = false
)

internal fun AiGatewayService.isTransient(statusCode: Int, message: String?): Boolean {
    if (statusCode in AiGatewayService.TRANSIENT_STATUSES) return true
    if (statusCode == 0) {
        val msg = message?.lowercase() ?: return true
        return msg.contains("timeout") || msg.contains("connection") || msg.contains("reset")
    }
    return false
}

internal fun AiGatewayService.extractErrorMessage(body: String): String? {
    val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    return (root["error"] as? JsonObject)
        ?.get("message")
        ?.let { it as? JsonPrimitive }
        ?.contentOrNull
}

internal suspend fun HttpResponse.bodyAsTextSafe(): String? = try {
    bodyAsText()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
