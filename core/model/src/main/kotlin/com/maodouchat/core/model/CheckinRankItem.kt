package com.maodouchat.core.model

/** 签到排行项。 */
data class CheckinRankItem(
    val userId: String,
    val streak: Int,
    val totalCount: Int
)
