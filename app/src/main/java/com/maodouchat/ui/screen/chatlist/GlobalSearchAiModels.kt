package com.maodouchat.ui.screen.chatlist

import com.maodouchat.core.model.GlobalSearchHit

internal data class AiSearchOutcome(
    val hits: List<GlobalSearchHit>,
    val excludedChats: Int,
    val noContext: Boolean
)
