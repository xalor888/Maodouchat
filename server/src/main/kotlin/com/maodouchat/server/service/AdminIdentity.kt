package com.maodouchat.server.service

import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.repository.UserRepository

/**
 * B13：管理身份——master（MASTER_ADMINS 静态/动态）与 moderator（Users.isModerator）两级角色。
 * 统一散落在各路由的「是否主管理员 / 是否审核员 / 是否具备内容审核权限」判定，单一事实源。
 */
data class AdminIdentity(
    val userId: String,
    val isMaster: Boolean,
    val isModerator: Boolean,
) {
    /** MASTER_ADMINS 继承受限内容审核权限（即使 isModerator=false）。 */
    val hasContentModerationAccess: Boolean get() = isMaster || isModerator
}

object AdminIdentityResolver {
    fun resolve(userId: String, userRepo: UserRepository): AdminIdentity = AdminIdentity(
        userId = userId,
        isMaster = AdminAccess.isAdmin(userId),
        isModerator = userRepo.isModerator(userId),
    )
}
