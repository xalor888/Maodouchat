package com.maodouchat.domain.messaging

// 画像叙事统计提示与拼接上限，自 app 侧 AiProfileNarrative 收敛；
// 消息抓取与模型调用仍留在 app 侧。
object AiProfileNarrativePolicy {

    const val MAX_CONTEXT_MESSAGES: Int = 60

    const val MAX_NARRATIVE_CHARS: Int = 6_000

    fun statsHint(stats: LocalStats): String =
        "消息 ${stats.messageCount} 条，活跃 ${stats.activeDays} 天，" +
            "主题 ${AiProfileTopTermsPolicy.displayTerms(stats.topTerms, "、")}"

    fun combineNarrative(statsHint: String, summary: String): String =
        "$statsHint。$summary".trim().take(MAX_NARRATIVE_CHARS)
}
