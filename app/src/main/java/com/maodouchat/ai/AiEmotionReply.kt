package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.repository.AiEnhanceHttp
import com.maodouchat.network.AiContextMessage
import com.maodouchat.network.AiEmotionReplyRequest
import com.maodouchat.network.AiEmotionReplyResponse
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * B4 · 情绪感知回复（本地情绪检测 + 服务端 AiGateway 回复生成）。
 *
 * 本地：基于关键词/表情词典检测最近消息的情绪（开心/难过/生气/焦虑/中性），
 *       纯本地规则，不引入 embedding。
 * 服务端：把消毒后的最近会话 POST /api/ai/enhance/emotion-reply，服务端复用
 *       AiGateway.suggestReplies（按情绪映射到「empathetic/warm/gentle/encouraging」等
 *       白名单语气）生成一条自然回复。
 *
 * 约束：
 * - 走 AiPromptSafetyPolicy 消毒；消息数上限 16（与服务端白名单一致）；
 * - 需 AI 处理同意 + 回复建议类开关（复用 AI_SUGGEST_REPLIES 网关）；
 * - 服务端输出只取单条回复文本并做长度上限，展示前可再叠加特权幻觉提示。
 */
object AiEmotionReply {

    fun isAllowed(context: Context): Boolean =
        AiPrivacyPreferences.consentAccepted(context) &&
            RuntimeFlags.isEnabled(context, RuntimeFlags.AI_SUGGEST_REPLIES)

    enum class Emotion(val wire: String) {
        HAPPY("happy"),
        SAD("sad"),
        ANGRY("angry"),
        ANXIOUS("anxious"),
        NEUTRAL("neutral")
    }

    data class EmotionResult(val emotion: Emotion, val confidence: Double)

    private val json = Json { ignoreUnknownKeys = true }

    /** 本地情绪检测（纯词典规则）。 */
    fun detectEmotion(texts: List<String>): EmotionResult {
        var happy = 0; var sad = 0; var angry = 0; var anxious = 0
        var totalHits = 0
        for (text in texts) {
            val sample = text.take(LOCAL_SCAN_CHARS)
            val hits = HAPPY_HITS.count { sample.contains(it) }
            val sHits = SAD_HITS.count { sample.contains(it) }
            val aHits = ANGRY_HITS.count { sample.contains(it) }
            val nHits = ANXIOUS_HITS.count { sample.contains(it) }
            happy += hits; sad += sHits; angry += aHits; anxious += nHits
            totalHits += hits + sHits + aHits + nHits
        }
        val best = listOf(
            happy to Emotion.HAPPY,
            sad to Emotion.SAD,
            angry to Emotion.ANGRY,
            anxious to Emotion.ANXIOUS
        ).maxByOrNull { it.first }
        if (best == null || best.first == 0 || totalHits == 0) {
            return EmotionResult(Emotion.NEUTRAL, 0.0)
        }
        return EmotionResult(best.second, best.first.toDouble() / totalHits)
    }

    /**
     * 生成一条情绪感知回复。服务端不可用/未同意时回退到本地模板（不消费 AI 预算）。
     */
    suspend fun reply(
        context: Context,
        database: AppDatabase,
        chatId: String
    ): Result<String> {
        val messages = withContext(Dispatchers.IO) {
            database.messageDao().getSearchableMessages(limit = 200)
                .map { it.toDomain() }
                .filter { it.chatId == chatId }
                .takeLast(MAX_CONTEXT_MESSAGES)
        }
        val plainTexts = messages.map { it.parsedContent() }
        val emotion = detectEmotion(plainTexts)
        if (!isAllowed(context)) {
            return Result.success(localFallback(emotion.emotion))
        }
        val contextMessages = messages.mapNotNull { message ->
            AiPromptSafetyPolicy.sanitizeContextLine(
                sender = message.senderId,
                text = message.parsedContent()
            )?.let { AiContextMessage(it.sender, it.text) }
        }
        if (contextMessages.isEmpty()) return Result.success(localFallback(emotion.emotion))
        val request = AiEmotionReplyRequest(
            messages = contextMessages,
            emotion = emotion.emotion.wire,
            chatId = chatId
        )
        val body = json.encodeToString(AiEmotionReplyRequest.serializer(), request)
        return try {
            val response = AiEnhanceHttp.post(
                context,
                "/api/ai/enhance/emotion-reply",
                body,
                AiEmotionReplyResponse.serializer()
            ).getOrNull()
            val text = response?.reply?.trim()?.take(MAX_REPLY_CHARS).orEmpty()
            Result.success(text.ifBlank { localFallback(context, emotion.emotion) })
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.success(localFallback(context, emotion.emotion))
        }
    }

    /** 8.48：本地回退模板国际化（此前硬编码中文，英文用户看到中文回复）。不声称执行任何动作，仅表达理解。 */
    private fun localFallback(context: Context, emotion: Emotion): String = when (emotion) {
        Emotion.HAPPY -> context.getString(R.string.ai_emotion_fallback_happy)
        Emotion.SAD -> context.getString(R.string.ai_emotion_fallback_sad)
        Emotion.ANGRY -> context.getString(R.string.ai_emotion_fallback_angry)
        Emotion.ANXIOUS -> context.getString(R.string.ai_emotion_fallback_anxious)
        Emotion.NEUTRAL -> context.getString(R.string.ai_emotion_fallback_neutral)
    }

    private val HAPPY_HITS = listOf("哈哈", "哈哈哈", "开心", "太好了", "棒", "喜欢", "耶", "嘻嘻", "lol", "haha", "great", "happy", "love")
    private val SAD_HITS = listOf("难过", "伤心", "哭了", "难受", "遗憾", "失落", "emo", "sad", "cry", "miss", "后悔")
    private val ANGRY_HITS = listOf("气死", "生气", "愤怒", "烦死了", "可恶", "恶心", "滚", "吵", "angry", "mad", "hate", "烦")
    private val ANXIOUS_HITS = listOf("焦虑", "担心", "害怕", "紧张", "来不及", "怎么办", "压力", "失眠", "worried", "anxious", "nervous", "stress")

    private const val LOCAL_SCAN_CHARS = 200
    private const val MAX_CONTEXT_MESSAGES = 16
    private const val MAX_REPLY_CHARS = 800
}
