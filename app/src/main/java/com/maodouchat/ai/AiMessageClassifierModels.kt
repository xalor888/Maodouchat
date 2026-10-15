package com.maodouchat.ai

enum class AiMessageClassifierCategory(val wire: String, val labelKey: String) {
    NOTICE("notice", "ai_enhance_classify_notice"),
    TODO("todo", "ai_enhance_classify_todo"),
    FINANCE("finance", "ai_enhance_classify_finance"),
    STUDY("study", "ai_enhance_classify_study"),
    TECH("tech", "ai_enhance_classify_tech"),
    SOCIAL("social", "ai_enhance_classify_social"),
    OTHER("other", "ai_enhance_classify_other")
}

data class Classification(val category: AiMessageClassifierCategory, val confidence: Double)
