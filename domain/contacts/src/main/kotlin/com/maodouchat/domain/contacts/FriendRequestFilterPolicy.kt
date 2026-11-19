package com.maodouchat.domain.contacts


// 好友申请搜索：申请人显示名、名字、id、申请附言四字段任一含查询词即命中。
// 自 ContactsListScreen 入站与出站两处逐字相同的 remember 过滤块收敛，共用同一谓词。
object FriendRequestFilterPolicy {

    fun matches(request: FriendRequestItem, query: String): Boolean =
        request.user.displayName.contains(query, ignoreCase = true) ||
            request.user.name.contains(query, ignoreCase = true) ||
            request.user.id.contains(query, ignoreCase = true) ||
            request.message.contains(query, ignoreCase = true)

    fun filter(requests: List<FriendRequestItem>, query: String): List<FriendRequestItem> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return requests
        return requests.filter { matches(it, trimmed) }
    }
}
