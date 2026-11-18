package com.maodouchat.domain.groups

/** 签到排行项。 */
data class CheckinRankItem(
    val userId: String,
    val streak: Int,
    val totalCount: Int
)
