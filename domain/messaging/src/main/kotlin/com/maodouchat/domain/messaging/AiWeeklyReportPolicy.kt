package com.maodouchat.domain.messaging

// 周报生成常量与结果截断，自 app 侧 AiWeeklyReportGeneration 收敛；
// 消息加载与缓存仍留在 app 侧。
object AiWeeklyReportPolicy {

    const val QUERY_LIMIT: Int = 2_000

    const val MAX_REPORT_MESSAGES: Int = 40

    const val MAX_REPORT_CHARS: Int = 20_000

    const val REPORT_TITLE: String = "本周群报"

    const val REPORT_MODE: String = "summary"

    fun truncateReport(report: String): String = report.trim().take(MAX_REPORT_CHARS)
}
