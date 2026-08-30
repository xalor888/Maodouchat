package com.maodouchat.server.repository

/**
 * B10：统一可见性策略（公开/联系人/私有 + 拉黑）。资料、动态、附近、搜索与 presence
 * 共享同一判定，避免各领域散落不一致的 visibility 分支。
 */
object VisibilityPolicy {
    val ALLOWED_VISIBILITIES = setOf("PUBLIC", "CONTACTS", "PRIVATE")

    fun normalize(value: String): String {
        val normalized = value.trim().uppercase()
        return if (normalized in ALLOWED_VISIBILITIES) normalized else "PRIVATE"
    }

    /** 作者/本人/联系人/拉黑 判定；CONTACTS 依赖社交图传入的 contactIds。 */
    fun isVisible(
        visibility: String,
        authorId: String,
        viewerId: String,
        contactIds: Set<String>,
        blockedUserIds: Set<String>,
    ): Boolean {
        if (authorId == viewerId) return true
        if (authorId in blockedUserIds) return false
        return when (normalize(visibility)) {
            "PUBLIC" -> true
            "CONTACTS" -> authorId in contactIds
            else -> false
        }
    }
}
