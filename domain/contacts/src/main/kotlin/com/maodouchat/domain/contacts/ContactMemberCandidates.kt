package com.maodouchat.domain.contacts

import com.maodouchat.core.model.User

// 新建群组/频道时选人列表的过滤与排序：空查询不过滤；在线的排前面，同状态按显示名排。
object ContactMemberCandidates {
    fun filterAndSort(contacts: List<User>, query: String): List<User> {
        val q = query.trim()
        val base = if (q.isEmpty()) contacts
        else contacts.filter { user ->
            user.displayName.contains(q, ignoreCase = true) ||
                user.name.contains(q, ignoreCase = true) ||
                user.id.contains(q, ignoreCase = true) ||
                user.email.contains(q, ignoreCase = true) ||
                user.nickname?.contains(q, ignoreCase = true) == true
        }
        return base.sortedWith(compareByDescending<User> { it.isOnline }.thenBy { it.displayName.lowercase() })
    }
}
