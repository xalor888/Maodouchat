package com.maodouchat.ai

import android.content.Context
import com.maodouchat.core.model.AiClassifyLexicon
import com.maodouchat.core.model.CategoryCount
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.repository.AiProfileRepository
import com.maodouchat.domain.conversation.ChatClassificationTally
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 会话级分类统计簇：重算分类统计并落库 / 读取缓存（纯本地 SQLCipher）；汇总走 :domain:conversation 的纯逻辑。 */
object AiChatClassificationStats {

    /** 重算某会话分类统计并落库；返回类别统计（按计数降序）。 */
    suspend fun classifyChat(context: Context, database: AppDatabase, chatId: String): List<CategoryCount> {
        val tallies = withContext(Dispatchers.IO) {
            // 8.48 修复：按会话查询——此前「全库最新 2000 条后 filter chatId」在活跃大库下
            // 目标会话不在最新窗口时统计为空/失真
            val messages = database.messageDao()
                .getSearchableMessagesForChat(chatId, limit = CLASSIFY_SCAN_MESSAGES)
                .map { it.toDomain() }
                .takeLast(CLASSIFY_SAMPLE_LIMIT)
            val results = messages.map { AiClassifyLexicon.classifyText(it.parsedContent()) }
            ChatClassificationTally.tally(results)
        }
        withContext(Dispatchers.IO) {
            AiProfileRepository.getInstance(context).saveChatClasses(chatId, tallies)
        }
        return tallies
    }

    /** 读取上次分类统计（仅本地）。 */
    suspend fun cached(context: Context, chatId: String): List<CategoryCount> =
        withContext(Dispatchers.IO) {
            AiProfileRepository.getInstance(context).getChatClasses(chatId)
        }

    private const val CLASSIFY_SCAN_MESSAGES = 2_000
    private const val CLASSIFY_SAMPLE_LIMIT = 200
}
