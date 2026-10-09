package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.repository.AiProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

// 画像生成簇：本地统计画像生成 + 可选服务端叙事摘要 + 落库。
internal object AiProfileBuild {

    /**
     * 生成本地统计画像；若允许且开关开启，再从服务端取叙事摘要（失败不阻断本地结果）。
     * 读取消息在 [Dispatchers.IO] 内进行，明文只存在于本机 SQLCipher 解密通道。
     */
    suspend fun build(
        context: Context,
        database: AppDatabase,
        chatId: String
    ): AiConversationProfile.ConversationProfile {
        val stats = withContext(Dispatchers.IO) {
            // 9.231：按会话查询——此前「全库最新 800 条后 filter chatId」在活跃大库下
            // 目标会话不在最新窗口时统计失真（同 8.48 分类器修复）
            val messages = database.messageDao().getSearchableMessagesForChat(chatId, limit = 800)
                .map { it.toDomain() }
            AiProfileStats.computeStats(messages)
        }
        val narrative = if (AiProfileGates.isAllowed(context) && stats.messageCount > 0) {
            try {
                AiProfileNarrative.fetchNarrative(context, database, chatId, stats)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val profile = AiConversationProfile.ConversationProfile(chatId = chatId, local = stats, narrative = narrative)
        withContext(Dispatchers.IO) {
            AiProfileRepository.getInstance(context).saveProfile(
                chatId = chatId,
                statsJson = json.encodeToString(AiConversationProfile.LocalStats.serializer(), stats),
                narrative = narrative
            )
        }
        return profile
    }

    private val json = Json { ignoreUnknownKeys = true }
}
