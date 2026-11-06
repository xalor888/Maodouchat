package com.maodouchat.core.model

/**
 * 系统入口目标（P08：自 `MainActivity` 迁出，入口消费与导航共用同一类型）。
 */
sealed interface NotificationTarget {
    val sessionGeneration: Long
    val ownerUserId: String
    data class Chat(
        val id: String,
        override val sessionGeneration: Long,
        override val ownerUserId: String,
        /** 8.41：消息「稍后提醒」点击 → 打开聊天后高亮原消息。 */
        val messageId: String? = null,
    ) : NotificationTarget
    data class AiTasks(
        val chatId: String,
        override val sessionGeneration: Long,
        override val ownerUserId: String,
    ) : NotificationTarget
    data class Post(
        val id: String,
        override val sessionGeneration: Long,
        override val ownerUserId: String,
    ) : NotificationTarget
    /** 深链接打开公开资料页（maodouchat://u/<username> 或 https://chat.mdou.me/u/<username>）。 */
    data class PublicProfile(
        val username: String,
        override val sessionGeneration: Long,
        override val ownerUserId: String,
    ) : NotificationTarget

    /**
     * 群邀请加入（`maodouchat:chat-invite:v1:` / `maodouchat://invite/` / `https://chat.mdou.me/join/`）。
     * 登录前到达时 ownerUserId 可能为空，登录后不得因 owner 校验丢弃（同 PublicProfile）。
     */
    data class GroupInvite(
        val code: String,
        override val sessionGeneration: Long,
        override val ownerUserId: String,
    ) : NotificationTarget
}
