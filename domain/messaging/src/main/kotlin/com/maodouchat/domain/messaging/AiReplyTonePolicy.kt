package com.maodouchat.domain.messaging

// AI 回复语气归一化：合法语气原样返回（trim + 大小写不敏感），其余回落 friendly；
// 自 ChatDetailOfflineSuggestions.generateAiSuggestions 收敛。
object AiReplyTonePolicy {

    private val validTones = setOf(
        "natural", "friendly", "formal", "concise", "warm",
        "humorous", "direct", "empathetic", "encouraging"
    )

    fun normalizeTone(tone: String): String {
        val normalized = tone.trim().lowercase()
        return if (normalized in validTones) normalized else "friendly"
    }
}
