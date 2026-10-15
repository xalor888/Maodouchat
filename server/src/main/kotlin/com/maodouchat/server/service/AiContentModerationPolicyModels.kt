package com.maodouchat.server.service

enum class Verdict { ALLOW, REVIEW, BLOCK }

data class Decision(
    val verdict: Verdict,
    val action: String,
    val category: String,
    val reason: String,
    val needsReview: Boolean
)
