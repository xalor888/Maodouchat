package com.maodouchat.server.service

// 离线唤醒入队簇：enqueue* 只是把路由元数据打包成 Delivery 交给 onQueued 观察者，
// FCM HTTP v1 已移除，这里不做任何真实远端推送（见类 KDoc）。

private const val ANNOUNCEMENT_TITLE_MAX = 80

fun FcmPushService.shutdown() {
    if (!closed.compareAndSet(false, true)) return
    logger.info("Wake push service shut down (no FCM transport)")
}

fun FcmPushService.enqueueIncomingCall(recipientId: String, senderId: String, isVideo: Boolean, callId: String = "") {
    if (closed.get() || recipientId.isBlank() || recipientId == senderId) return
    record(
        Delivery(
            recipientId = recipientId,
            isCall = true,
            data = buildMap {
                put("type", "INCOMING_CALL")
                put("senderId", senderId)
                put("callType", if (isVideo) "VIDEO" else "AUDIO")
                put("recipientId", recipientId)
                if (callId.isNotBlank()) put("callId", callId)
            }
        )
    )
}

fun FcmPushService.enqueuePostInteraction(
    recipientId: String,
    actorId: String,
    postId: String,
    interaction: String,
    @Suppress("UNUSED_PARAMETER") preview: String? = null,
    commentId: String? = null
) {
    if (closed.get() || recipientId.isBlank() || postId.isBlank() || recipientId == actorId ||
        interaction !in setOf("LIKE", "COMMENT", "COMMENT_LIKE", "REPLY")
    ) return
    val data = mutableMapOf(
        "type" to "POST_INTERACTION",
        "postId" to postId,
        "actorId" to actorId,
        "interaction" to interaction,
        "recipientId" to recipientId
    )
    // P07：唤醒载荷不得夹带正文/预览；客户端只凭 id 拉投影。
    commentId?.takeIf(String::isNotBlank)?.let { data["commentId"] = it }
    record(Delivery(recipientId = recipientId, isCall = false, data = data))
}

fun FcmPushService.enqueueGroupInvite(
    recipientId: String,
    fromUserId: String,
    inviteId: String,
    chatId: String,
    action: String
) {
    if (closed.get() || recipientId.isBlank() || recipientId == fromUserId) return
    if (action != "CREATED") return
    if (inviteId.isBlank() || chatId.isBlank()) return
    record(
        Delivery(
            recipientId = recipientId,
            isCall = false,
            data = mapOf(
                "type" to "GROUP_INVITE",
                "action" to action,
                "inviteId" to inviteId,
                "chatId" to chatId,
                "fromUserId" to fromUserId,
                "recipientId" to recipientId
            )
        )
    )
}

fun FcmPushService.enqueueFriendRequest(recipientId: String, fromUserId: String, requestId: String, action: String) {
    if (closed.get() || recipientId.isBlank() || recipientId == fromUserId) return
    if (action !in setOf("CREATED", "ACCEPTED") || requestId.isBlank()) return
    record(
        Delivery(
            recipientId = recipientId,
            isCall = false,
            data = mapOf(
                "type" to "FRIEND_REQUEST",
                "action" to action,
                "requestId" to requestId,
                "fromUserId" to fromUserId,
                "recipientId" to recipientId
            )
        )
    )
}

fun FcmPushService.enqueueAnnouncement(recipientId: String, announcementId: String, title: String, level: String) {
    if (closed.get() || recipientId.isBlank() || announcementId.isBlank()) return
    record(
        Delivery(
            recipientId = recipientId,
            isCall = false,
            breakthroughDnd = level == "EMERGENCY",
            data = mapOf(
                "type" to "ANNOUNCEMENT",
                "announcementId" to announcementId,
                "title" to title.trim().take(ANNOUNCEMENT_TITLE_MAX),
                "level" to level,
                "recipientId" to recipientId
            )
        )
    )
}

private fun FcmPushService.record(delivery: Delivery) {
    if (closed.get()) return
    onQueued?.invoke(delivery)
}
