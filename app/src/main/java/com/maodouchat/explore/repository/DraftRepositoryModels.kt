package com.maodouchat.explore.repository

data class ExploreDraft(
    val composerText: String = "",
    val selectedVisibility: String = "PRIVATE",
    val imageUris: List<String> = emptyList()
)
