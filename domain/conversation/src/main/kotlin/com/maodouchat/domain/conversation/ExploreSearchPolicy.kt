package com.maodouchat.domain.conversation

// Explore 动态/评论本地搜索：正文、作者名、作者 id 任一含查询词（忽略大小写）即命中。
// 自 app 侧四处逐字相同的行内谓词收敛：ExploreFeedScreen（动态）、ExploreMomentsScreen
// （动态）、ExploreCommentsDialog（评论）、ExplorePostDetailScreen（评论）；
// 空白查询由 filter 直接返回全量，与各调用点原先的 isBlank 分支一致。
object ExploreSearchPolicy {

    fun matches(content: String, authorName: String, authorId: String, query: String): Boolean =
        content.contains(query, ignoreCase = true) ||
            authorName.contains(query, ignoreCase = true) ||
            authorId.contains(query, ignoreCase = true)

    fun <T> filter(
        items: List<T>,
        query: String,
        contentOf: (T) -> String,
        authorNameOf: (T) -> String,
        authorIdOf: (T) -> String,
    ): List<T> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return items
        return items.filter { matches(contentOf(it), authorNameOf(it), authorIdOf(it), trimmed) }
    }
}
