package com.maodouchat.domain.conversation

import com.maodouchat.core.model.PostDto

// 按 id 找单条动态：动态操作簇、动态详情簇与作者主页 4 处逐字相同的 firstOrNull；
// 调用方保留各自的 ?: return / 可空缓存判定。
object ExplorePostLookupPolicy {

    fun findById(posts: List<PostDto>, postId: String): PostDto? =
        posts.firstOrNull { it.id == postId }
}
