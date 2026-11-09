package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageStatus

/** 时间线投影的路由与自发消息状态判定。纯逻辑：调用方按路由分派到三个分支。 */
object TimelineProjectionRoute {
    enum class Route { SENDER_KEY_REQUEST, EVENT, DATA }

    fun decide(contentType: String, hasEvent: Boolean): Route = when {
        contentType == TYPE_SENDER_KEY_REQUEST -> Route.SENDER_KEY_REQUEST
        hasEvent -> Route.EVENT
        else -> Route.DATA
    }

    fun projectedStatus(senderUserId: String, ownerUserId: String): MessageStatus =
        if (senderUserId == ownerUserId) MessageStatus.SENT else MessageStatus.DELIVERED
}
