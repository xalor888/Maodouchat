package com.maodouchat.domain.calls

import com.maodouchat.core.model.GroupPeerConnectionState

fun GroupCallPolicy.isActive(state: GroupPeerConnectionState): Boolean =
    state == GroupPeerConnectionState.CONNECTING ||
        state == GroupPeerConnectionState.CONNECTED ||
        state == GroupPeerConnectionState.RECONNECTING

/** Exactly one side creates an offer for each mesh edge, preventing SDP glare. */
fun GroupCallPolicy.shouldInitiateMeshEdge(selfUserId: String, peerUserId: String): Boolean =
    selfUserId.isNotBlank() && peerUserId.isNotBlank() && selfUserId < peerUserId
