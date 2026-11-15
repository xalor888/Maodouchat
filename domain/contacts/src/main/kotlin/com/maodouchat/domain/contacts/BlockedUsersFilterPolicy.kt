package com.maodouchat.domain.contacts

import com.maodouchat.core.model.UserDto

// 黑名单搜索：名字、id 任一含查询词即命中。
// 自 SettingsBlockedUsersScreen 的 filteredBlocked 收敛。
object BlockedUsersFilterPolicy {

    fun matches(user: UserDto, query: String): Boolean =
        user.name.contains(query, ignoreCase = true) ||
            user.id.contains(query, ignoreCase = true)

    fun filter(users: List<UserDto>, query: String): List<UserDto> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return users
        return users.filter { matches(it, trimmed) }
    }
}
