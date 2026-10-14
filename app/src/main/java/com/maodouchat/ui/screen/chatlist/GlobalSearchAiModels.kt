package com.maodouchat.ui.screen.chatlist

internal data class AiSearchOutcome(
    val hits: List<GlobalSearchHit>,
    val excludedChats: Int,
    val noContext: Boolean
)
