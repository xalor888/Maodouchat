package com.maodouchat.core.model

data class AiSearchOutcome(
    val hits: List<GlobalSearchHit>,
    val excludedChats: Int,
    val noContext: Boolean
)
