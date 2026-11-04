package com.maodouchat.core.model

data class AnnouncementData(
    val id: String,
    val title: String,
    val content: String,
    val level: String,
    val startsAt: Long,
    val expiresAt: Long,
    val status: String,
    val acked: Boolean = false
)
