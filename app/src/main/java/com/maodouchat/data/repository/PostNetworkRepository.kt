package com.maodouchat.data.repository

import com.maodouchat.network.ApiService
import com.maodouchat.network.PostDto

/**
 * 动态（帖子）的**远端**读取与点赞（G328c）。
 *
 * 与 `explore/repository/PostMutationRepository` 的分工：那个是**带本地日志与乐观更新**的
 * 变更通道（发布/评论/点赞都先落 journal，失败可回放）；本类只做**直取**——
 * 作者主页那一屏要的是「拉一页帖子」「点赞/取消」，不需要乐观更新与回放。
 * 两者都保留：把作者主页也接到 journal 上会引入它不需要的状态机。
 *
 * 刻意很薄（不缓存、不重试）。`FeedRepository`（explore 层）也调过 `getPosts`，
 * 已一并改走这里，使「拉帖子」在 data 层只有一个入口。
 */
internal class PostNetworkRepository(
    private val postsApi: suspend (String, Int, Long?, String?, String?) -> Result<List<PostDto>> =
        { token, limit, before, beforeId, authorId -> ApiService.getPosts(token, limit, before, beforeId, authorId) },
    private val likeApi: suspend (String, String) -> Result<PostDto> =
        { token, postId -> ApiService.likePost(token, postId) },
    private val unlikeApi: suspend (String, String) -> Result<PostDto> =
        { token, postId -> ApiService.unlikePost(token, postId) },
) {
    suspend fun posts(
        token: String,
        limit: Int = 40,
        before: Long? = null,
        beforeId: String? = null,
        authorId: String? = null,
    ): Result<List<PostDto>> = postsApi(token, limit, before, beforeId, authorId)

    suspend fun like(token: String, postId: String): Result<PostDto> = likeApi(token, postId)

    suspend fun unlike(token: String, postId: String): Result<PostDto> = unlikeApi(token, postId)
}
