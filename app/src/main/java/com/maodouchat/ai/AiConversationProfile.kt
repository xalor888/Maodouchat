package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.model.Message
import com.maodouchat.data.repository.AiProfileRepository
import com.maodouchat.data.repository.AiEnhanceHttp
import com.maodouchat.network.AiContextMessage
import com.maodouchat.network.AiConversationProfileRequest
import com.maodouchat.network.AiConversationProfileResponse
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * B4 · 会话画像（本地 SQLCipher 优先 + 服务端 AiGateway 叙事增强）。
 *
 * 本地画像：从解密后的本地消息（SQLCipher 库内读取）统计
 * - 消息量 / 活跃天数 / 时段分布（早午晚）
 * - 表情使用
 * - 高频主题词（CJK 二元组 + 拉丁词，本地轻量统计，不引入 embedding）
 *
 * 服务端叙事：可选地把最近一段会话消毒后 POST /api/ai/enhance/conversation-profile，
 * 由服务端复用 AiGateway.summarize 生成一段「对端画像/话题画像」叙事，缓存到本地。
 *
 * 约束：
 * - 走 AiPromptSafetyPolicy 消毒（控制符剥离、截断、标注不可信）；
 * - 密聊会话不参与（结果不应落可搜索缓存）；
 * - 需已同意 AI 处理 + 本地画像开关（默认开）。
 */
object AiConversationProfile {

    /** 本地画像开关（与 AI 处理同意独立，默认开，纯本机）。 */
    fun isAllowed(context: Context): Boolean =
        AiPrivacyPreferences.consentAccepted(context) && isLocalProfileEnabled(context)

    fun isLocalProfileEnabled(context: Context): Boolean =
        RuntimeFlags.isEnabled(context, RuntimeFlags.AI_SUMMARY)

    @Serializable
    data class LocalStats(
        val messageCount: Int = 0,
        val activeDays: Int = 0,
        val morning: Int = 0,
        val afternoon: Int = 0,
        val evening: Int = 0,
        val night: Int = 0,
        val emojiCount: Int = 0,
        val topTerms: List<String> = emptyList()
    )

    data class ConversationProfile(
        val chatId: String,
        val local: LocalStats,
        val narrative: String? = null,
        val updatedAt: Long = System.currentTimeMillis()
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 生成本地统计画像；若允许且开关开启，再从服务端取叙事摘要（失败不阻断本地结果）。
     * 读取消息在 [Dispatchers.IO] 内进行，明文只存在于本机 SQLCipher 解密通道。
     */
    suspend fun build(context: Context, database: AppDatabase, chatId: String): ConversationProfile {
        val stats = withContext(Dispatchers.IO) {
            val messages = database.messageDao().getSearchableMessages(limit = 800)
                .map { it.toDomain() }
                .filter { it.chatId == chatId }
            computeStats(messages)
        }
        val narrative = if (isAllowed(context) && stats.messageCount > 0) {
            try {
                fetchNarrative(context, database, chatId, stats)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val profile = ConversationProfile(chatId = chatId, local = stats, narrative = narrative)
        withContext(Dispatchers.IO) {
            AiProfileRepository.getInstance(context).saveProfile(
                chatId = chatId,
                statsJson = json.encodeToString(LocalStats.serializer(), stats),
                narrative = narrative
            )
        }
        return profile
    }

    /** 读取上次画像缓存（仅本地，不发请求）。 */
    suspend fun cached(context: Context, database: AppDatabase, chatId: String): ConversationProfile? {
        val repository = AiProfileRepository.getInstance(context)
        val row = withContext(Dispatchers.IO) { repository.getProfile(chatId) } ?: return null
        val stats = runCatching {
            json.decodeFromString(LocalStats.serializer(), row.statsJson)
        }.getOrElse { LocalStats() }
        return ConversationProfile(chatId, stats, row.narrative, row.updatedAt)
    }

    private suspend fun fetchNarrative(
        context: Context,
        database: AppDatabase,
        chatId: String,
        stats: LocalStats
    ): String {
        val messages = withContext(Dispatchers.IO) {
            database.messageDao().getSearchableMessages(limit = 200)
                .map { it.toDomain() }
                .filter { it.chatId == chatId && it.senderId != selfSenderId() }
                .takeLast(PROFILE_CONTEXT_MESSAGES)
        }
        if (messages.isEmpty()) return ""
        val contextMessages = messages.mapNotNull { message ->
            AiPromptSafetyPolicy.sanitizeContextLine(
                sender = message.senderId,
                text = message.parsedContent()
            )?.let { AiContextMessage(it.sender, it.text) }
        }
        if (contextMessages.isEmpty()) return ""
        val request = AiConversationProfileRequest(
            messages = contextMessages,
            chatId = chatId
        )
        val body = json.encodeToString(AiConversationProfileRequest.serializer(), request)
        return AiEnhanceHttp.post(
            context,
            "/api/ai/enhance/conversation-profile",
            body,
            AiConversationProfileResponse.serializer()
        ).getOrNull()?.summary?.trim()?.take(MAX_NARRATIVE_CHARS).orEmpty()
    }

    private fun computeStats(messages: List<Message>): LocalStats {
        if (messages.isEmpty()) return LocalStats()
        val days = messages.map { java.util.Calendar.getInstance().apply { timeInMillis = it.timestamp }.get(java.util.Calendar.DAY_OF_YEAR) }.toSet()
        var morning = 0; var afternoon = 0; var evening = 0; var night = 0; var emoji = 0
        val termCounts = HashMap<String, Int>()
        for (message in messages) {
            val hour = java.util.Calendar.getInstance().apply { timeInMillis = message.timestamp }.get(java.util.Calendar.HOUR_OF_DAY)
            when (hour) {
                in 5..11 -> morning++
                in 12..17 -> afternoon++
                in 18..23 -> evening++
                else -> night++
            }
            val text = message.parsedContent()
            emoji += EMOJI_PATTERN.findAll(text).count()
            collectTerms(text, termCounts)
        }
        val topTerms = termCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .take(MAX_TERMS)
        return LocalStats(
            messageCount = messages.size,
            activeDays = days.size,
            morning = morning,
            afternoon = afternoon,
            evening = evening,
            night = night,
            emojiCount = emoji,
            topTerms = topTerms
        )
    }

    /** 轻量主题词：CJK 二元组 + 拉丁词，仅统计，不引入 embedding/词典权重。 */
    private fun collectTerms(text: String, counts: MutableMap<String, Int>) {
        val cleaned = text.lowercase().filterNot { it.isWhitespace() || it.isDigit() }
        val cjk = StringBuilder()
        cleaned.forEach { char ->
            if (char.code in 0x4E00..0x9FFF) cjk.append(char) else cjk.append(' ')
        }
        cjk.split(' ').forEach { segment ->
            if (segment.length >= 2) {
                for (index in 0 until segment.length - 1) {
                    val bigram = segment.substring(index, index + 2)
                    counts[bigram] = (counts[bigram] ?: 0) + 1
                }
            }
        }
        cleaned.split(Regex("[^a-z0-9]+")).forEach { word ->
            if (word.length in 3..20 && STOP_WORDS.none { it == word }) {
                counts[word] = (counts[word] ?: 0) + 1
            }
        }
    }

    private fun selfSenderId(): String {
        val tokenManager = com.maodouchat.network.TokenManager.getInstanceOrNull() ?: return ""
        return tokenManager.getUserId().orEmpty()
    }

    private val EMOJI_PATTERN = Regex("""[\uD83C-\uDBFF\uDC00-\uDFFF\u2600-\u27BF\uFE0F]""")
    private val STOP_WORDS = setOf("the", "and", "for", "you", "your", "that", "this", "with", "have", "from", "are", "not", "was", "has", "but", "what", "all", "can", "out", "who", "when", "will", "just", "like", "about")

    private const val PROFILE_CONTEXT_MESSAGES = 60
    private const val MAX_NARRATIVE_CHARS = 6_000
    private const val MAX_TERMS = 10
}
