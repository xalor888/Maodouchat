package com.maodouchat.server.repository

data class RuleMatch(
    val ruleId: String,
    val action: String,
    val matched: String?,
    val needsReview: Boolean,
    val eventId: String? = null
)

data class Evaluation(
    val blocked: Boolean,
    val action: String? = null,
    val message: String? = null,
    val matches: List<RuleMatch> = emptyList()
)
