package com.maodouchat.server.repository

import kotlinx.serialization.Serializable

@Serializable
data class CheckinDto(
    val chatId: String,
    val userId: String,
    val date: String,
    val streak: Int,
    val totalCount: Int,
    val todayRank: Int,
    val todayCount: Int,
    val alreadyCheckedIn: Boolean,
    val checkedAt: Long
)

@Serializable
data class CheckinRankEntry(
    val userId: String,
    val streak: Int,
    val totalCount: Int,
    val lastCheckedAt: Long
)
