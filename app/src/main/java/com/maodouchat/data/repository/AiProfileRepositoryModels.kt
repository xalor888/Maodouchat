package com.maodouchat.data.repository

data class ProfileRow(val chatId: String, val statsJson: String, val narrative: String?, val updatedAt: Long)
data class ArchiveRow(val chatId: String, val score: Int, val reason: String, val suggestedAt: Long)
data class WeeklyReportRow(val chatId: String, val weekStart: Long, val weekEnd: Long, val report: String, val model: String?, val createdAt: Long)
data class CategoryCount(val category: String, val count: Int, val confidence: Double)
