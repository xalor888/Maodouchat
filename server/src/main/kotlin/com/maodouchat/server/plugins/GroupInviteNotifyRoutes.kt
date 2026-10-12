package com.maodouchat.server.plugins

import com.maodouchat.server.model.GroupInvitationDto
import com.maodouchat.server.model.GroupInviteEventPayload
import com.maodouchat.server.model.WsMessage
import com.maodouchat.server.service.FcmPushService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 群邀请通知簇：群邀请事件实时推送（从 Routing.kt 按域拆出）。 */
/** 9.3xx：群邀请事件（CREATED/ACCEPTED/DECLINED/CANCELLED）实时推送；CREATED 额外 FCM 唤醒离线被邀请人。 */
internal suspend fun notifyGroupInvite(
    json: Json,
    invite: GroupInvitationDto,
    action: String,
    pushService: FcmPushService
) {
    val payload = json.encodeToString(
        GroupInviteEventPayload.serializer(),
        GroupInviteEventPayload(action = action, invite = invite)
    )
    val envelope = json.encodeToString(WsMessage.serializer(), WsMessage("GROUP_INVITE", payload))
    // 目标用户实时感知邀请；邀请人/管理员侧同步状态（撤销/拒绝）
    LocalRealtimeBus.publish(invite.userId, envelope)
    if (invite.inviterId.isNotBlank() && invite.inviterId != invite.userId) {
        LocalRealtimeBus.publish(invite.inviterId, envelope)
    }
    if (action == "CREATED") {
        pushService.enqueueGroupInvite(
            recipientId = invite.userId,
            fromUserId = invite.inviterId,
            inviteId = invite.id,
            chatId = invite.chatId,
            action = action
        )
    }
}
