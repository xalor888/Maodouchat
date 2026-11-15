package com.maodouchat.domain.conversation

import com.maodouchat.core.model.PostDto

// 动态单条替换：按 id 把更新后的动态写回列表，其余项原样保留；
// 收敛作者资料页 5 处与动态操作簇 1 处逐字相同的行内 map。
object ExplorePostReplacePolicy {

    fun replace(posts: List<PostDto>, postId: String, updated: PostDto): List<PostDto> =
        posts.map { if (it.id == postId) updated else it }
}
