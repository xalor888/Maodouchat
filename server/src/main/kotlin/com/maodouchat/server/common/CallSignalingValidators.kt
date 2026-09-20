package com.maodouchat.server.common

import com.maodouchat.server.repository.ConversationQueryRepository

/**
 * G45：通话信令校验的**唯一**归属地。
 *
 * 这族判定原先住在 `plugins/Validation.kt`（route 装配层）。`CallSignalingService` 需要它们，
 * 而 `service/ → plugins/` 是架构棘轮禁止的反向依赖，所以整族下沉到中立包。
 *
 * **语义逐字保留**，尤其是三个最容易被"顺手改好"的地方：
 * - 信令类型是**小写**的 `offer` / `answer` / `ice-candidate` / `hang-up` / `busy` / `reject`
 *   —— 线上客户端就是按这个形状发的，写成 `CALL_OFFER` 会让每一通真实通话被判成非法；
 * - 载荷只校验「非终端类型必须非空 + 长度上限」，**不解析 SDP 结构**
 *   —— SDP 是否合法由 WebRTC 层负责，在这里加格式校验等于凭空收紧协议；
 * - `callId` 上限是 **100**，不是 64。
 */
object CallSignalingValidators {

    private const val MAX_SIGNALING_PAYLOAD_LENGTH = 32_768
    private const val MAX_CALL_ID_LENGTH = 100
    private const val MAX_MESH_CALL_MEMBERS = 6

    private val ALLOWED_SIGNALING_TYPES =
        setOf("offer", "answer", "ice-candidate", "hang-up", "busy", "reject")

    private val CALL_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,100}$")

    fun isValidSignalPayload(type: String, payload: String): Boolean {
        val payloadRequired = type !in setOf("hang-up", "busy", "reject")
        return type in ALLOWED_SIGNALING_TYPES &&
            (!payloadRequired || payload.isNotBlank()) &&
            payload.length <= MAX_SIGNALING_PAYLOAD_LENGTH
    }

    /** Non-blank session id required so hangup/clear never wipe unrelated 1:1 signaling. */
    fun isValidCallId(callId: String): Boolean =
        callId.isNotBlank() && callId.length <= MAX_CALL_ID_LENGTH && CALL_ID_REGEX.matches(callId)

    fun isValidGroupSignalMetadata(
        groupId: String,
        groupMemberIds: List<String>,
        groupInvite: Boolean,
        callId: String,
        fromUserId: String,
        toUserId: String,
        conversationQueryRepository: ConversationQueryRepository,
    ): Boolean {
        if (groupId.isBlank()) return groupMemberIds.isEmpty() && !groupInvite
        if (callId.isBlank()) return false
        if (!CALL_ID_REGEX.matches(groupId) || groupMemberIds.size !in 2..MAX_MESH_CALL_MEMBERS) return false
        val distinctMembers = groupMemberIds.distinct()
        if (distinctMembers.size != groupMemberIds.size ||
            fromUserId !in distinctMembers ||
            toUserId !in distinctMembers
        ) {
            return false
        }
        val chat = conversationQueryRepository.getById(groupId) ?: return false
        if (!chat.isGroup) return false
        val actualMembers = chat.participants.map { it.id }.toSet()
        return distinctMembers.all { it in actualMembers }
    }
}
