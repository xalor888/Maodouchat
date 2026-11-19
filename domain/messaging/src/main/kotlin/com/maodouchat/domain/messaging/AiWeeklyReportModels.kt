package com.maodouchat.domain.messaging

data class WeeklyReport(
    val chatId: String,
    val weekStart: Long,
    val weekEnd: Long,
    val report: String,
    val model: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
