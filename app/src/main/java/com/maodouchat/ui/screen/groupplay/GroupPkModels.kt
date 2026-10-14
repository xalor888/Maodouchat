package com.maodouchat.ui.screen.groupplay

/** 群 PK 列表项。 */
data class PkListItem(
    val id: String,
    val leftTitle: String,
    val rightTitle: String,
    val active: Boolean,
    val leftCount: Int,
    val rightCount: Int,
    val totalVoters: Int,
    val myChoice: String?
)
