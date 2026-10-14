package com.maodouchat.ui.screen.groupplay

/** 群接龙列表项。 */
data class ChainListItem(
    val id: String,
    val title: String,
    val topic: String,
    val active: Boolean,
    val entryCount: Int,
    val maxEntries: Int,
    val myJoined: Boolean
)

/** 群接龙明细项。 */
data class ChainEntryItem(
    val id: String,
    val userId: String,
    val sequence: Int,
    val content: String
)
