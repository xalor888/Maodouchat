package com.maodouchat.server.repository

import com.maodouchat.server.model.UserPrivacyResponse
import com.maodouchat.server.service.AccountLifecycleService
import com.maodouchat.server.service.BlockService
import com.maodouchat.server.service.CredentialService
import com.maodouchat.server.service.PrivacyService
import com.maodouchat.server.service.ProfileService

class UserRepository {

    /** B13：批量处置字段（SUSPEND/POST/MESSAGE 单项，或组合清除）。 */
    enum class UserDispositionField { SUSPEND, POST, MESSAGE, MESSAGE_AND_POST, ALL }

    /** B13：批量处置语义——EXTEND 保长（maxOf）、SET 直接覆盖、CLEAR 置零。 */
    enum class UserDispositionMode { EXTEND, SET, CLEAR }

    data class BulkDispositionResult(val updated: List<String>, val skipped: List<String>)

    /** B13：批量用户设置字段（searchable/showStatus/showOnline 布尔开关）。 */
    enum class UserSettingsField { SEARCHABLE, SHOW_STATUS, SHOW_ONLINE }

    /**
     * 修改密码：先校验旧密码，成功后用 BCrypt cost=12 重哈希
     * @return true 改密成功；false 用户不存在或旧密码错误
     */
    internal val credentialService = CredentialService()

    internal val accountLifecycleService = AccountLifecycleService()

    internal val privacyService = PrivacyService()

    /** 通过唯一用户名查找用户（不含 @ 前缀，大小写不敏感）——公开主页专用（匿名脱敏）。 */
    internal val profileService = ProfileService()

    internal val blockService = BlockService()

    internal companion object {
        private const val MAX_NAME_LENGTH = 50
        val ALLOWED_ONLINE_VISIBILITIES = setOf("everyone", "contacts", "nobody")

        fun normalizeOnlineVisibility(value: String?, showOnlineFallback: Boolean = true): String {
            val raw = value?.trim()?.lowercase().orEmpty()
            return when (raw) {
                "everyone", "contacts", "nobody" -> raw
                else -> if (showOnlineFallback) "everyone" else "nobody"
            }
        }

        fun onlineVisibleToAnyone(showOnline: Boolean, visibility: String?): Boolean {
            if (!showOnline) return false
            return normalizeOnlineVisibility(visibility, showOnline) != "nobody"
        }

        fun onlineVisibleToViewer(
            showOnline: Boolean,
            visibility: String?,
            targetId: String,
            viewerId: String
        ): Boolean {
            if (targetId == viewerId) return true
            if (!showOnline) return false
            return when (normalizeOnlineVisibility(visibility, showOnline)) {
                "everyone" -> true
                "contacts" -> FriendRepository().areFriends(targetId, viewerId)
                else -> false
            }
        }

        /**
         * 8.49 修复 BOOTSTRAP_FIRST_USER_AS_ADMIN 并发竞态：READ COMMITTED 下两个并发
         * 注册事务互看不到对方未提交的插入，双双判定自己是"第一个用户"→双主管理员。
         * 单实例部署（compose 固定单 server）下用进程锁把整个注册事务串行化：
         * 先提交者成为唯一"第一个"，后到者在锁内能看到已提交行。注册本身低频，锁开销可忽略。
         * （锁本体随 register 迁入 CredentialService。）
         */
    }
}

/** Result of account deactivation; [orphanedAttachmentIds] need disk cleanup by the caller. */
data class AccountDeactivationResult(
    val deletedAt: Long,
    val orphanedAttachmentIds: List<String> = emptyList(),
    val avatarUrl: String? = null
)

data class AvatarReplacementResult(
    val previousUrl: String?,
    val currentUrl: String?
)

data class PrivacyUpdateResult(
    val privacy: UserPrivacyResponse,
    val onlineRevoked: Boolean,
    val statusRevoked: Boolean
)

internal enum class Restriction { MESSAGE, POST, SUSPEND }
