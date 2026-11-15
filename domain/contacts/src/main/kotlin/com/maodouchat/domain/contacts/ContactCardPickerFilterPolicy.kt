package com.maodouchat.domain.contacts

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User

// 名片选择器成员搜索：展示名、名字任一含查询词即命中。
// 自 ChatDetailContactCards 的 ContactCardPickerDialog 收敛。
object ContactCardPickerFilterPolicy {

    fun matches(user: User, query: String): Boolean =
        user.displayName.contains(query, ignoreCase = true) ||
            user.name.contains(query, ignoreCase = true)

    fun filter(contacts: List<Pair<Chat, User>>, query: String): List<Pair<Chat, User>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return contacts
        return contacts.filter { (_, user) -> matches(user, trimmed) }
    }
}
