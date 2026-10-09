package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.network.AiContextMessage
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * B4 · 情绪感知回复（本地情绪检测 + 服务端 AiGateway 回复生成）。
 *
 * 模型：用户自配的本机 OpenAI 兼容接口按情绪映射语气生成一条自然回复。
 *
 * 实现已按簇拆到 AiEmotionLexicon（本地检测）与 AiEmotionFallback（本地回退模板），
 * 这里只保留门禁、公开类型与回复装配流程。
 */
object AiEmotionReply {

    fun isAllowed(context: Context): Boolean =
        AiPrivacyPreferences.mayUploadCloudContext(context) &&
            RuntimeFlags.isEnabled(context, RuntimeFlags.AI_MASTER)

    enum class Emotion(val wire: String) {
        HAPPY("happy"),
        SAD("sad"),
        ANGRY("angry"),
        ANXIOUS("anxious"),
        NEUTRAL("neutral")
    }

    data class EmotionResult(val emotion: Emotion, val confidence: Double)

    /** 本地情绪检测（纯词典规则）。 */
    fun detectEmotion(texts: List<String>): EmotionResult =
        AiEmotionLexicon.detectEmotion(texts)

    /**
     * 生成一条情绪感知回复。服务端不可用/未同意时回退到本地模板（不消费 AI 预算）。
     */
    suspend fun reply(
        context: Context,
        database: AppDatabase,
        chatId: String
    ): Result<String> {
        val messages = withContext(Dispatchers.IO) {
            // 9.231：按会话查询——此前「全库最新 200 条后 filter chatId」在活跃大库下
            // 目标会话不在最新窗口时取不到上下文，情绪检测与回复生成失真（同 8.48 分类器修复）
            database.messageDao().getSearchableMessagesForChat(chatId, limit = MAX_CONTEXT_MESSAGES)
                .map { it.toDomain() }
                .takeLast(MAX_CONTEXT_MESSAGES)
        }
        val plainTexts = messages.map { it.parsedContent() }
        val emotion = detectEmotion(plainTexts)
        if (!isAllowed(context)) {
            return Result.success(AiEmotionFallback.localFallback(context, emotion.emotion))
        }
        val contextMessages = messages.mapNotNull { message ->
            AiPromptSafetyPolicy.sanitizeContextLine(
                sender = message.senderId,
                text = message.parsedContent()
            )?.let { AiContextMessage(it.sender, it.text) }
        }
        if (contextMessages.isEmpty()) return Result.success(AiEmotionFallback.localFallback(context, emotion.emotion))
        val tone = when (emotion.emotion) {
            Emotion.HAPPY -> "warm"
            Emotion.SAD -> "empathetic"
            Emotion.ANGRY -> "gentle"
            Emotion.ANXIOUS -> "encouraging"
            Emotion.NEUTRAL -> "friendly"
        }
        return try {
            val replies = com.maodouchat.ai.agent.LocalAiGateway.suggestReplies(
                context,
                contextMessages,
                tone,
                1
            ).getOrNull().orEmpty()
            val text = replies.firstOrNull()?.trim()?.take(MAX_REPLY_CHARS).orEmpty()
            Result.success(text.ifBlank { AiEmotionFallback.localFallback(context, emotion.emotion) })
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.success(AiEmotionFallback.localFallback(context, emotion.emotion))
        }
    }

    private const val MAX_CONTEXT_MESSAGES = 16
    private const val MAX_REPLY_CHARS = 800
}
