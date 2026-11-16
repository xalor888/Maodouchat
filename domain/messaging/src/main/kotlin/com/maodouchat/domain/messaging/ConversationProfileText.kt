package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ConversationProfile

// 会话画像复制文本：统计→时段分布→Top 8 热词→叙述的顺序与分隔符；三处文案模板由调用方传入。
fun conversationProfileText(
    profile: ConversationProfile,
    statsText: (messageCount: Int, activeDays: Int) -> String,
    timeDistributionText: (morning: Int, afternoon: Int, evening: Int, night: Int) -> String,
    topTermsText: (terms: String) -> String,
): String = buildString {
    append(statsText(profile.local.messageCount, profile.local.activeDays))
    append("\n")
    append(
        timeDistributionText(
            profile.local.morning,
            profile.local.afternoon,
            profile.local.evening,
            profile.local.night
        )
    )
    if (profile.local.topTerms.isNotEmpty()) {
        append("\n")
        append(topTermsText(profile.local.topTerms.take(8).joinToString(" · ")))
    }
    profile.narrative?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
}
