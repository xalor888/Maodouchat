package com.maodouchat.domain.groups

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
) {
    val votedChoiceTitle: String?
        get() = when (myChoice) {
            "left" -> leftTitle
            "right" -> rightTitle
            else -> null
        }
}
