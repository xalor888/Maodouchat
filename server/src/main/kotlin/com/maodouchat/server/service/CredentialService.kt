package com.maodouchat.server.service

import com.maodouchat.server.db.Users
import com.maodouchat.server.model.UserResponse
import org.jetbrains.exposed.sql.ResultRow

/** 姓名长度上限（包内共享；原 `UserRepository` companion 私有常量）。 */
internal const val MAX_NAME_LENGTH = 50

/** 已注销用户脱敏名（包内共享）。 */
internal const val DELETED_USER_NAME = "已注销用户"

/** 邮箱归一化（自 `UserRepository` 私有成员提升为包内共享）。 */
internal fun String.normalizedEmail(): String = trim().lowercase()

/** 用户行 → 私有资料（自 `UserRepository` 私有成员提升为包内共享）。 */
internal fun ResultRow.toPrivateUser(isOnlineOverride: Boolean? = null): UserResponse {
    return UserResponse(
        id = this[Users.id],
        name = this[Users.name],
        email = this[Users.email],
        avatar = this[Users.avatar],
        status = this[Users.status],
        isOnline = isOnlineOverride ?: this[Users.isOnline],
        isModerator = this[Users.isModerator],
        lastSeen = this[Users.lastSeen],
        username = this[Users.username]
    )
}

internal fun ResultRow.toPublicUser(lastSeenVisible: Boolean = false, anonymous: Boolean = false): UserResponse {
        if (this[Users.deletedAt] != null) {
            return UserResponse(
                id = this[Users.id],
                name = DELETED_USER_NAME,
                email = "",
                avatar = null,
                status = "账号已注销",
                isOnline = false
            )
        }
        // 匿名访问（公开主页）隐藏 status/isOnline/lastSeen——外部链接页只需展示姓名与头像。
        val onlineVisible = !anonymous && this[Users.showOnline] && this[Users.isOnline]
        val statusVisible = if (anonymous) "" else if (this[Users.showStatus]) this[Users.status] else ""
        // 精确 lastSeen 仅对「存在 1:1 会话」的 viewer 可见（8.30 隐私修复）；
        // 目录/搜索/公开主页一律 0，与好友列表、群成员等界面一致。
        val lastSeenVisibleValue = if (!anonymous && lastSeenVisible) this[Users.lastSeen] else 0
        return UserResponse(
            id = this[Users.id],
            name = this[Users.name],
            email = "",
            avatar = this[Users.avatar],
            status = statusVisible,
            isOnline = onlineVisible,
            lastSeen = lastSeenVisibleValue,
            username = this[Users.username]
        )
    }
/** SQL 唯一冲突判定（包内共享；其余各仓储的私有拷贝后续逐步收敛）。 */
internal fun isUniqueViolation(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current is java.sql.SQLException && current.sqlState == "23505") return true
        val message = current.message.orEmpty().lowercase()
        if (message.contains("unique") || message.contains("duplicate key")) return true
        current = current.cause
    }
    return false
}

/**
 * 凭证服务：注册 / 登录（含 TOTP 二因子）/ 改密 / 邮箱重置 / 密码校验。
 * 自 `UserRepository` 拆出（B02「拆 CredentialService」），与
 * `AccountLifecycleService` / `PrivacyService` / `BlockService` 同构。
 */
class CredentialService(
    internal val mfaService: com.maodouchat.server.service.MfaService =
        com.maodouchat.server.service.MfaService(),
) {
    internal val registrationLock = Any()

}
