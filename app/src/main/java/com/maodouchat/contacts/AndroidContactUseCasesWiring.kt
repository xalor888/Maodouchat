package com.maodouchat.contacts

import android.app.Application
import com.maodouchat.core.model.ContactMutationUseCase
import com.maodouchat.core.model.FriendRequestUseCase
import com.maodouchat.data.repository.AppRepositories
import com.maodouchat.data.repository.FriendCacheStore
import com.maodouchat.domain.contacts.DefaultContactMutationUseCase
import com.maodouchat.domain.contacts.DefaultFriendRequestUseCase
import com.maodouchat.network.ApiService
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.session.CurrentSession

/** app 侧装配：联系人变更与好友申请用例的网络调用与会话门禁，UI 层只拿成品用例。 */
internal fun createAndroidContactMutationUseCase(
    application: Application,
): ContactMutationUseCase = DefaultContactMutationUseCase(
    sessionProvider = {
        val snap = CurrentSession.snapshot()
        Pair(snap.userId, snap.token)
    },
    setNicknameLocal = { userId, nickname ->
        AppRepositories.users.setNickname(userId, nickname)
    },
    removeFriendApi = { token, id -> ApiService.removeFriend(token, id) },
    blockUserApi = { token, id -> ApiService.blockUser(token, id) },
    unblockUserApi = { token, id -> ApiService.unblockUser(token, id) },
    isOwnerSessionCurrent = { expectedUserId, liveToken, liveUserId ->
        BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
            liveToken = liveToken,
            liveUserId = liveUserId,
        )
    },
    onFriendRemoved = { ownerUserId, friendId ->
        FriendCacheStore.remove(application, friendId, ownerUserId)
    },
    onUserBlocked = { ownerUserId, targetId ->
        FriendCacheStore.remove(application, targetId, ownerUserId)
    },
)

internal fun createAndroidFriendRequestUseCase(
    application: Application,
): FriendRequestUseCase = DefaultFriendRequestUseCase(
    sessionProvider = {
        val snap = CurrentSession.snapshot()
        Pair(snap.userId, snap.token)
    },
    getIncomingRequestsApi = { token -> ApiService.getIncomingFriendRequests(token) },
    getOutgoingRequestsApi = { token -> ApiService.getOutgoingFriendRequests(token) },
    sendFriendRequestApi = { token, target, note -> ApiService.sendFriendRequest(token, target, note) },
    acceptFriendRequestApi = { token, id -> ApiService.acceptFriendRequest(token, id) },
    rejectFriendRequestApi = { token, id -> ApiService.rejectFriendRequest(token, id) },
    cancelFriendRequestApi = { token, id -> ApiService.cancelFriendRequest(token, id) },
    isOwnerSessionCurrent = { expectedUserId, liveToken, liveUserId ->
        BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
            liveToken = liveToken,
            liveUserId = liveUserId,
        )
    },
    onFriendAccepted = { newFriend ->
        AppRepositories.users.insertUsers(listOf(newFriend))
        FriendCacheStore.add(application, newFriend.id, CurrentSession.ownerUserId())
    },
)
