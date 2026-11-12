package com.maodouchat.contacts.sync

import android.content.Context
import com.maodouchat.data.local.dao.UserDao
import com.maodouchat.data.repository.FriendCacheStore
import com.maodouchat.domain.contacts.DefaultContactsRealtimeSyncCoordinator
import com.maodouchat.security.BackgroundSessionGate

/** ContactsViewModel 的实时同步协调器接线：Room 写库、好友缓存与会话门禁由 app 侧提供。 */
internal fun createAndroidContactsRealtimeSyncCoordinator(
    context: Context,
    userDao: UserDao,
    onNotificationCenterItem: ((id: String, title: String, subtitle: String, message: String?) -> Unit)? = null,
): DefaultContactsRealtimeSyncCoordinator = DefaultContactsRealtimeSyncCoordinator(
    applyRealtimeVisibility = { userId, isOnline, onlineRevoked, statusRevoked, updatedAt ->
        userDao.applyRealtimeVisibility(
            userId = userId,
            isOnline = isOnline,
            onlineRevoked = onlineRevoked,
            statusRevoked = statusRevoked,
            updatedAt = updatedAt
        )
    },
    addFriendToCache = { friendId, ownerUserId -> FriendCacheStore.add(context, friendId, ownerUserId) },
    sessionMayContinue = { owner, token, userId ->
        BackgroundSessionGate.mayContinue(expectedUserId = owner, liveToken = token, liveUserId = userId)
    },
    onNotificationCenterItem = onNotificationCenterItem,
)
