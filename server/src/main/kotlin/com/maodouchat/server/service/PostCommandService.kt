package com.maodouchat.server.service

import java.util.concurrent.ConcurrentHashMap

/** B10：动态/评论写命令子域 + 图片 claim 管理。 */
class PostCommandService(
    internal val feedQueryService: FeedQueryService,
) {
    // filename → postId 映射：图片先上传，post 后创建；创建时扫描 imageUrls 注册此映射。
    internal val imageFilenameToPostId = ConcurrentHashMap<String, String>()
    internal val imageClaimLock = Any()
    internal val safeImageFilenamePattern = Regex("^[A-Za-z0-9_.-]+$")
    internal val imageMetaCapacityLock = Any()
    internal val MAX_IMAGE_META_SIZE = 10_000
}
