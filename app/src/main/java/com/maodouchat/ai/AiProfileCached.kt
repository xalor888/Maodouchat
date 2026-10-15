package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.repository.AiProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

// 画像缓存读取簇：只读本地画像缓存，不发任何请求。
internal object AiProfileCached {

    /** 读取上次画像缓存（仅本地，不发请求）。 */
    suspend fun cached(
        context: Context,
        database: AppDatabase,
        chatId: String
    ): ConversationProfile? {
        val repository = AiProfileRepository.getInstance(context)
        val row = withContext(Dispatchers.IO) { repository.getProfile(chatId) } ?: return null
        val stats = runCatching {
            json.decodeFromString(LocalStats.serializer(), row.statsJson)
        }.getOrElse { LocalStats() }
        return ConversationProfile(chatId, stats, row.narrative, row.updatedAt)
    }

    private val json = Json { ignoreUnknownKeys = true }
}
