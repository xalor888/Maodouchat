package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.repository.AiProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * B4 · 消息分类（纯本地 SQLCipher 规则分类，无服务端调用）。
 *
 * 用轻量词典对本地消息分类，类别：通知 / 待办 / 财务 / 学习 / 技术 / 情感闲聊 / 其他。
 * 会话级结果（每类别计数 + 置信度）写入独立 SQLCipher 库，UI 可在会话内展示
 * 「分类统计」或按类别筛选本地消息。
 *
 * 约束：
 * - 纯本地规则，不引入 embedding/模型权重（OnDeviceEmbeddingGate 保持关闭）；
 * - 明文只在 SQLCipher 解密通道内处理，分类结果不离开本机；
 * - 词典是启发式，不构成任何事实/特权声明。
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
    fun classifyText(text: String): Classification {
        val sample = text.take(SCAN_CHARS)
        val scores = Category.entries.map { category ->
            val hits = lexiconFor(category).count { sample.contains(it, ignoreCase = true) }
            category to hits
        }
        val best = scores.maxByOrNull { it.second } ?: (Category.OTHER to 0)
        return if (best.second == 0) {
            Classification(Category.OTHER, 0.2)
        } else {
            val total = scores.sumOf { it.second }
            Classification(best.first, best.second.toDouble() / total)
        }
    }

    /** 重算某会话分类统计并落库；返回类别统计（按计数降序）。 */
    suspend fun classifyChat(context: Context, database: AppDatabase, chatId: String): List<AiProfileRepository.CategoryCount> {
        val tallies = withContext(Dispatchers.IO) {
            // 8.48 修复：按会话查询——此前「全库最新 2000 条后 filter chatId」在活跃大库下
            // 目标会话不在最新窗口时统计为空/失真
            val messages = database.messageDao()
                .getSearchableMessagesForChat(chatId, limit = CLASSIFY_SCAN_MESSAGES)
                .map { it.toDomain() }
                .takeLast(CLASSIFY_SAMPLE_LIMIT)
            val counts = HashMap<Category, Int>()
            // 9.231：置信度按类别均值——此前把全会话平均置信度复制给每个类别，
            // 低命中类别也显示高置信度，UI 排序/展示失真
            val confSums = HashMap<Category, Double>()
            for (message in messages) {
                val result = classifyText(message.parsedContent())
                counts[result.category] = (counts[result.category] ?: 0) + 1
                confSums[result.category] = (confSums[result.category] ?: 0.0) + result.confidence
            }
            counts.map { (category, count) ->
                AiProfileRepository.CategoryCount(
                    category = category.wire,
                    count = count,
                    confidence = (confSums[category] ?: 0.0) / count
                )
            }.sortedByDescending { it.count }
        }
        withContext(Dispatchers.IO) {
            AiProfileRepository.getInstance(context).saveChatClasses(chatId, tallies)
        }
        return tallies
    }

    /** 读取上次分类统计（仅本地）。 */
    suspend fun cached(context: Context, chatId: String): List<AiProfileRepository.CategoryCount> =
        withContext(Dispatchers.IO) {
            AiProfileRepository.getInstance(context).getChatClasses(chatId)
        }

    private fun lexiconFor(category: Category): List<String> = when (category) {
        Category.NOTICE -> listOf(
            "通知", "公告", "提醒", "请注意", "系统消息", "上线", "维护", "变更",
            "notice", "announcement", "reminder", "maintenance"
        )
        Category.TODO -> listOf(
            "待办", "任务", "记得", "别忘了", "安排", "提交", "截止", "周会", "跟进",
            "todo", "task", "deadline", "follow up", "assign"
        )
        Category.FINANCE -> listOf(
            "转账", "付款", "收款", "账单", "余额", "发票", "报销", "工资", "优惠", "红包",
            "pay", "transfer", "bill", "invoice", "refund", "price"
        )
        Category.STUDY -> listOf(
            "学习", "课程", "作业", "考试", "复习", "笔记", "阅读", "论文", "书",
            "study", "homework", "exam", "course", "notes", "lecture"
        )
        Category.TECH -> listOf(
            "代码", "部署", "接口", "bug", "修复", "服务器", "数据库", "版本", "上线",
            "code", "deploy", "api", "server", "database", "bug", "fix", "release"
        )
        Category.SOCIAL -> listOf(
            "哈哈", "哈哈哈", "开心", "周末", "吃饭", "聚会", "电影", "旅行", "晚安", "早安",
            "haha", "lol", "weekend", "dinner", "movie", "trip", "good night"
        )
        Category.OTHER -> emptyList()
    }

    private const val SCAN_CHARS = 300
    private const val CLASSIFY_SCAN_MESSAGES = 2_000
    private const val CLASSIFY_SAMPLE_LIMIT = 200
}
