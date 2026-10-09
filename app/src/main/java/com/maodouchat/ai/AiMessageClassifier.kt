package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.repository.AiProfileRepository

/**
 * B4 · 消息分类（纯本地 SQLCipher 规则分类，无服务端调用）。
 *
 * 用轻量词典对本地消息分类，类别：通知 / 待办 / 财务 / 学习 / 技术 / 情感闲聊 / 其他。
 * 会话级结果（每类别计数 + 置信度）写入独立 SQLCipher 库，UI 可在会话内展示
 * 「分类统计」或按类别筛选本地消息。
 *
 * 实现已按簇拆到 AiClassifyLexicon（词典/单条分类）与 AiChatClassificationStats
 * （会话统计/落库），这里只保留门禁、公开类型与统一入口。
 */
object AiMessageClassifier {

    fun isAllowed(context: Context): Boolean = true

    enum class Category(val wire: String, val labelKey: String) {
        NOTICE("notice", "ai_enhance_classify_notice"),
        TODO("todo", "ai_enhance_classify_todo"),
        FINANCE("finance", "ai_enhance_classify_finance"),
        STUDY("study", "ai_enhance_classify_study"),
        TECH("tech", "ai_enhance_classify_tech"),
        SOCIAL("social", "ai_enhance_classify_social"),
        OTHER("other", "ai_enhance_classify_other")
    }

    data class Classification(val category: Category, val confidence: Double)

    /** 单条消息分类（纯函数）。 */
    fun classifyText(text: String): Classification =
        AiClassifyLexicon.classifyText(text)

    /** 重算某会话分类统计并落库；返回类别统计（按计数降序）。 */
    suspend fun classifyChat(context: Context, database: AppDatabase, chatId: String): List<AiProfileRepository.CategoryCount> =
        AiChatClassificationStats.classifyChat(context, database, chatId)

    /** 读取上次分类统计（仅本地）。 */
    suspend fun cached(context: Context, chatId: String): List<AiProfileRepository.CategoryCount> =
        AiChatClassificationStats.cached(context, chatId)
}
