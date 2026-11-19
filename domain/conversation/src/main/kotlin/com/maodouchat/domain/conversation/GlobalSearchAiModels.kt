package com.maodouchat.domain.conversation

data class AiSearchOutcome(
    val hits: List<GlobalSearchHit>,
    val excludedChats: Int,
    val noContext: Boolean
)
