package com.maodouchat.ai

/**
 * 消息分类词典簇：启发式关键词词典 + 单条消息分类（纯函数）。
 *
 * 词典是启发式，不构成任何事实/特权声明；分类结果不离开本机。
 */
object AiClassifyLexicon {

    /** 单条消息分类（纯函数）。 */
    fun classifyText(text: String): Classification {
        val sample = text.take(SCAN_CHARS)
        val scores = AiMessageClassifierCategory.entries.map { category ->
            val hits = lexiconFor(category).count { sample.contains(it, ignoreCase = true) }
            category to hits
        }
        val best = scores.maxByOrNull { it.second } ?: (AiMessageClassifierCategory.OTHER to 0)
        return if (best.second == 0) {
            Classification(AiMessageClassifierCategory.OTHER, 0.2)
        } else {
            val total = scores.sumOf { it.second }
            Classification(best.first, best.second.toDouble() / total)
        }
    }

    internal fun lexiconFor(category: AiMessageClassifierCategory): List<String> = when (category) {
        AiMessageClassifierCategory.NOTICE -> listOf(
            "通知", "公告", "提醒", "请注意", "系统消息", "上线", "维护", "变更",
            "notice", "announcement", "reminder", "maintenance"
        )
        AiMessageClassifierCategory.TODO -> listOf(
            "待办", "任务", "记得", "别忘了", "安排", "提交", "截止", "周会", "跟进",
            "todo", "task", "deadline", "follow up", "assign"
        )
        AiMessageClassifierCategory.FINANCE -> listOf(
            "转账", "付款", "收款", "账单", "余额", "发票", "报销", "工资", "优惠", "红包",
            "pay", "transfer", "bill", "invoice", "refund", "price"
        )
        AiMessageClassifierCategory.STUDY -> listOf(
            "学习", "课程", "作业", "考试", "复习", "笔记", "阅读", "论文", "书",
            "study", "homework", "exam", "course", "notes", "lecture"
        )
        AiMessageClassifierCategory.TECH -> listOf(
            "代码", "部署", "接口", "bug", "修复", "服务器", "数据库", "版本", "上线",
            "code", "deploy", "api", "server", "database", "bug", "fix", "release"
        )
        AiMessageClassifierCategory.SOCIAL -> listOf(
            "哈哈", "哈哈哈", "开心", "周末", "吃饭", "聚会", "电影", "旅行", "晚安", "早安",
            "haha", "lol", "weekend", "dinner", "movie", "trip", "good night"
        )
        AiMessageClassifierCategory.OTHER -> emptyList()
    }

    private const val SCAN_CHARS = 300
}
