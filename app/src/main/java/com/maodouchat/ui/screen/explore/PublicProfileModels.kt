package com.maodouchat.ui.screen.explore

/**
 * 公开个人主页数据
 */
data class PublicProfileData(
    val id: String,
    val name: String,
    val username: String? = null,
    val avatar: String? = null,
    val status: String = "",
    val isOnline: Boolean = false,
    val isModerator: Boolean = false,
    val lastSeen: Long = 0
)
