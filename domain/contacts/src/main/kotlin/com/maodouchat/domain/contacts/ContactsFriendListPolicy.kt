package com.maodouchat.domain.contacts

import com.maodouchat.core.model.User

// 好友列表：排除本人、只留好友 id 集合内用户，按显示名小写升序。
// 自 AndroidContactsRepository 的 observeFriends 与 cachedFriends 两处逐字相同链路收敛；
// loadFriends 的排序一步同走 sortByDisplayName，昵称回填链路不动。
object ContactsFriendListPolicy {

    fun sortByDisplayName(users: List<User>): List<User> =
        users.sortedBy { it.displayName.lowercase() }

    fun visibleFriends(users: List<User>, ownerUserId: String, friendIds: Set<String>): List<User> =
        sortByDisplayName(users.filter { it.id != ownerUserId && it.id in friendIds })
}
