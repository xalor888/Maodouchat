package com.maodouchat.server.repository

import com.maodouchat.server.db.Users
import com.maodouchat.server.model.UserPrivacyResponse
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** 隐私服务（B02「拆 PrivacyService」）：在线/状态/搜索/动态可见性读写。 */
class PrivacyService {

    fun getPrivacy(userId: String): UserPrivacyResponse? {
        return transaction {
            Users.selectAll().where { Users.id eq userId }.firstOrNull()?.let {
                UserPrivacyResponse(
                    showOnline = it[Users.showOnline],
                    showStatus = it[Users.showStatus],
                    searchable = it[Users.searchable],
                    defaultPostVisibility = normalizeVisibility(it[Users.defaultPostVisibility]),
                    onlineVisibility = normalizeOnlineVisibility(it[Users.onlineVisibility], it[Users.showOnline])
                )
            }
        }
    }

    fun updatePrivacyWithTransitions(
        userId: String,
        showOnline: Boolean? = null,
        showStatus: Boolean? = null,
        searchable: Boolean? = null,
        defaultPostVisibility: String? = null,
        onlineVisibility: String? = null
    ): PrivacyUpdateResult? {
        return transaction {
            val normalizedVisibility = defaultPostVisibility?.let(::normalizeVisibility)
            val previous = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
                ?: return@transaction null
            val previousOnlineVis = normalizeOnlineVisibility(previous[Users.onlineVisibility], previous[Users.showOnline])
            val nextOnlineVis = onlineVisibility?.let(::normalizeOnlineVisibility)
                ?: showOnline?.let { if (it) previousOnlineVis.takeUnless { vis -> vis == "nobody" } ?: "everyone" else "nobody" }
            Users.update({ Users.id eq userId }) {
                if (showOnline != null) it[Users.showOnline] = showOnline
                if (nextOnlineVis != null) {
                    it[Users.onlineVisibility] = nextOnlineVis
                    it[Users.showOnline] = nextOnlineVis != "nobody"
                }
                if (showStatus != null) it[Users.showStatus] = showStatus
                if (searchable != null) it[Users.searchable] = searchable
                if (normalizedVisibility != null) it[Users.defaultPostVisibility] = normalizedVisibility
            }
            val privacy = UserPrivacyResponse(
                showOnline = (nextOnlineVis ?: previousOnlineVis) != "nobody",
                showStatus = showStatus ?: previous[Users.showStatus],
                searchable = searchable ?: previous[Users.searchable],
                defaultPostVisibility = normalizedVisibility
                    ?: normalizeVisibility(previous[Users.defaultPostVisibility]),
                onlineVisibility = nextOnlineVis ?: previousOnlineVis
            )
            PrivacyUpdateResult(
                privacy = privacy,
                onlineRevoked = previousOnlineVis != "nobody" && privacy.onlineVisibility == "nobody",
                statusRevoked = previous[Users.showStatus] && !privacy.showStatus
            )
        }
    }

    private fun normalizeVisibility(value: String): String {
        return if (value in ALLOWED_POST_VISIBILITIES) value else "PUBLIC"
    }

    private fun normalizeOnlineVisibility(value: String?, showOnlineFallback: Boolean = true): String {
        val raw = value?.trim()?.lowercase().orEmpty()
        return when (raw) {
            "everyone", "contacts", "nobody" -> raw
            else -> if (showOnlineFallback) "everyone" else "nobody"
        }
    }

    private companion object {
        val ALLOWED_POST_VISIBILITIES = setOf("PUBLIC", "CONTACTS", "PRIVATE")
    }
}
