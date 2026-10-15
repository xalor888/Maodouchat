package com.maodouchat.server.service

// 投递执行（队列/fallback/重试/签名发送）：原 BotWebhookService 成员按域拆出的扩展函数（同包调用，零行为改动）。

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun BotWebhookService.enqueue(delivery: suspend () -> Unit) {
    val queue = activeQueue() ?: run {
        // 服务未 start / 已 shutdown 时静默丢弃会掩盖 bot 能力“看起来没反应”的问题，
        // 此前直接丢弃（webhook bot 连收件箱兜底都没有）→ 事件永久丢失。
        val dropped = droppedNotRunning.incrementAndGet()
        if (dropped == 1L || dropped % 100L == 0L) {
            logger.warn("Bot webhook service not running; falling back direct delivery ({} total)", dropped)
        }
        runFallback(delivery)
        return
    }
    if (queue.trySend(delivery).isSuccess) return
    // 队列满（1024）：slow webhook 场景极易触发，丢弃会让事件永久丢失 → fallback 执行一次
    val dropped = droppedDeliveries.incrementAndGet()
    if (dropped == 1L || dropped % 100L == 0L) {
        logger.warn("Bot webhook queue full; falling back direct delivery ({} total)", dropped)
    }
    runFallback(delivery)
}

/** 8.50 修复 M3：在受控并发下执行一次 fallback 投递，满则丢弃。 */
internal fun BotWebhookService.runFallback(delivery: suspend () -> Unit) {
    if (!fallbackSemaphore.tryAcquire()) {
        val dropped = droppedFallbackOverflow.incrementAndGet()
        if (dropped == 1L || dropped % 100L == 0L) {
            logger.warn("Bot webhook fallback overflow; dropped {} events", dropped)
        }
        return
    }
    fallbackScope.launch {
        try {
            delivery()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            logger.warn("Bot webhook fallback delivery failed", e)
        } finally {
            fallbackSemaphore.release()
        }
    }
}

internal suspend fun BotWebhookService.deliverWebhook(
    outboxId: String,
    url: String,
    body: String,
    ts: Long,
    tokenHash: String,
    botId: String
): DeliveryOutcome {
    // 抢占租约：PENDING 且（无租约 或 租约已过期）才可认领，原子更新防多实例重投。
    if (!claimLease(outboxId)) return DeliveryOutcome.SKIPPED
    var attempts = 0
    for (attempt in 1..WEBHOOK_MAX_ATTEMPTS) {
        attempts = attempt
        try {
            postJson(url, body, ts, tokenHash, botId)
            finalizeOutbox(outboxId, "DELIVERED", attempts)
            return DeliveryOutcome.DELIVERED
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            val retry = isRetryableWebhookFailure(e) && attempt < WEBHOOK_MAX_ATTEMPTS
            if (retry) {
                logger.warn(
                    "Bot webhook attempt {}/{} failed for bot {}; retrying",
                    attempt,
                    WEBHOOK_MAX_ATTEMPTS,
                    botId,
                    e
                )
                delay(WEBHOOK_RETRY_BASE_MS * attempt)
            } else {
                logger.warn(
                    "Bot webhook delivery failed for bot {} after {} attempt(s)",
                    botId,
                    attempt,
                    e
                )
                finalizeOutbox(outboxId, "DEAD", attempts)
                return DeliveryOutcome.DEAD
            }
        }
    }
    finalizeOutbox(outboxId, "DEAD", attempts)
    return DeliveryOutcome.DEAD
}

internal fun BotWebhookService.postJson(url: String, body: String, ts: Long, tokenHash: String, botId: String) {
    val signingInput = "$ts.$body"
    val signature = hmacSha256Hex(tokenHash, signingInput)
    val response = com.maodouchat.server.common.postPinnedWebhookJson(
        url = url,
        body = body,
        headers = mapOf(
            "User-Agent" to "Maodouchat-BotWebhook/1.0",
            "X-Maodouchat-Bot-Id" to botId,
            "X-Maodouchat-Timestamp" to ts.toString(),
            "X-Maodouchat-Signature" to "sha256=$signature"
        ),
        connectTimeoutMs = 3_000,
        readTimeoutMs = 3_000
    )
    if (response.statusCode !in 200..299) {
        throw WebhookHttpException(response.statusCode, botId)
    }
}

internal fun BotWebhookService.isRetryableWebhookFailure(error: Exception): Boolean {
    if (error is SecurityException || error is IllegalArgumentException) return false
    if (error is WebhookHttpException) {
        val code = error.statusCode
        return code == 408 || code == 429 || code in 500..599
    }
    return true
}
