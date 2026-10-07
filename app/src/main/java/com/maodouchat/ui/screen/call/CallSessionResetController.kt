package com.maodouchat.ui.screen.call

import com.maodouchat.call.GroupCallCapabilities

// 会话重置簇：从 CallViewModel 纯搬移——呼出/呼入前重置 VM 状态的四个
// inline lambda 原样搬入，各 controller 接线改方法引用。语句顺序与原实现逐字一致。
internal class CallSessionResetController(
    private val beginCallSession: (peerId: String, incoming: Boolean) -> Long,
    private val newCallId: () -> String,
    private val setEndingCall: (Boolean) -> Unit,
    private val setActiveCallId: (String) -> Unit,
    private val setActiveGroupId: (String) -> Unit,
    private val setMeshGroupMemberIds: (List<String>) -> Unit,
    private val setGroupMemberIds: (Set<String>) -> Unit,
    private val setPendingOfferSdp: (String?) -> Unit,
    private val setCallLogOwnerUserId: (String) -> Unit,
) {
    fun resetForNewOutgoingCall(peerId: String): Long {
        setEndingCall(false)
        val session = beginCallSession(peerId, false)
        setActiveCallId(newCallId())
        setActiveGroupId("")
        setMeshGroupMemberIds(emptyList())
        // 8.55：呼出时快照账号，作为通话记录写入的 expectedUserId 守卫
        setCallLogOwnerUserId(com.maodouchat.session.CurrentSession.ownerUserId())
        return session
    }

    fun resetForNewGroupCall(chatId: String, remoteMembers: List<String>, selfUserId: String): Long {
        setEndingCall(false)
        val session = beginCallSession(chatId, false)
        setActiveCallId(newCallId())
        setActiveGroupId(chatId)
        setMeshGroupMemberIds((remoteMembers + selfUserId).sorted())
        return session
    }

    fun resetForNewIncomingCall(
        contactId: String,
        offerSdp: String,
        callId: String,
        groupId: String,
        groupMemberIds: List<String>,
    ): Pair<Boolean, List<GroupCallParticipantUi>> {
        setPendingOfferSdp(offerSdp)
        setEndingCall(false)
        beginCallSession(contactId, true)
        setActiveCallId(callId)
        val selfUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        val normalizedMembers = groupMemberIds.filter(String::isNotBlank).distinct()
        val isGroup = groupId.isNotBlank() &&
            GroupCallCapabilities.canStartMesh(normalizedMembers.size) &&
            selfUserId in normalizedMembers
        setActiveGroupId(if (isGroup) groupId else "")
        setMeshGroupMemberIds(if (isGroup) normalizedMembers.sorted() else emptyList())
        val members = if (isGroup) normalizedMembers.filter { it != selfUserId }.toSet() else emptySet()
        setGroupMemberIds(members)
        return isGroup to members.map { GroupCallParticipantUi(it) }
    }

    fun markIncomingSessionOwner() {
        // 8.55：呼入时快照账号，作为通话记录写入的 expectedUserId 守卫
        setCallLogOwnerUserId(com.maodouchat.session.CurrentSession.ownerUserId())
    }
}
