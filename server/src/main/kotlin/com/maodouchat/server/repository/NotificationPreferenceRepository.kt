package com.maodouchat.server.repository

import com.maodouchat.server.db.NotificationPreferences
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.NotificationSettingsRequest
import com.maodouchat.server.model.NotificationSettingsResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class NotificationPreferenceRepository {

    fun getSettings(userId: String): NotificationSettingsResponse = transaction {
        NotificationPreferences.selectAll()
            .where { NotificationPreferences.userId eq userId }
            .firstOrNull()
            ?.toResponse()
            ?: NotificationSettingsResponse()
    }

    /**
     * 8.48 修复 H8：批量获取通知设置（FCM 批量投递用），缺省用户回默认设置。
     */
    fun getSettingsBatch(userIds: List<String>): Map<String, NotificationSettingsResponse> = transaction {
        if (userIds.isEmpty()) return@transaction emptyMap()
        val found = NotificationPreferences.selectAll()
            .where { NotificationPreferences.userId inList userIds }
            .associate { it[NotificationPreferences.userId] to it.toResponse() }
        userIds.associateWith { found[it] ?: NotificationSettingsResponse() }
    }

    fun updateSettings(userId: String, request: NotificationSettingsRequest): NotificationSettingsResponse = transaction {
        val owner = Users.selectAll().where { Users.id eq userId }.forUpdate().firstOrNull()
            ?: return@transaction NotificationSettingsResponse()
        if (owner[Users.deletedAt] != null) return@transaction NotificationSettingsResponse()
        val now = System.currentTimeMillis()
        val existing = NotificationPreferences.selectAll()
            .where { NotificationPreferences.userId eq userId }
            .firstOrNull()
        // 8.34 修复：hour 字段为遗留冗余（DND 判定只读 minute），此前两者可发散——只改小时
        // 不生效且 GET 回显矛盾的 hour。统一以分钟为唯一事实源：minute 优先，仅 hour 时换算，
        // 写库时 hour 恒由 minute 推导，杜绝发散。
        val existingStartMinute = existing?.get(NotificationPreferences.dndStartMinute) ?: (22 * 60)
        val existingEndMinute = existing?.get(NotificationPreferences.dndEndMinute) ?: (7 * 60)
        val effStartMinute = (request.dndStartMinute
            ?: request.dndStartHour?.let { it.coerceIn(0, 23) * 60 }
            ?: existingStartMinute).coerceIn(0, 1439)
        val effEndMinute = (request.dndEndMinute
            ?: request.dndEndHour?.let { it.coerceIn(0, 23) * 60 }
            ?: existingEndMinute).coerceIn(0, 1439)

        if (existing == null) {
            NotificationPreferences.insert {
                it[NotificationPreferences.userId] = userId
                it[NotificationPreferences.enableNotifications] = request.enableNotifications ?: true
                it[NotificationPreferences.soundEnabled] = request.soundEnabled ?: true
                it[NotificationPreferences.previewEnabled] = request.previewEnabled ?: true
                it[NotificationPreferences.ringtoneEnabled] = request.ringtoneEnabled ?: true
                it[NotificationPreferences.dndStartHour] = effStartMinute / 60
                it[NotificationPreferences.dndEndHour] = effEndMinute / 60
                it[NotificationPreferences.dndEnabled] = request.dndEnabled ?: false
                it[NotificationPreferences.dndStartMinute] = effStartMinute
                it[NotificationPreferences.dndEndMinute] = effEndMinute
                it[NotificationPreferences.updatedAt] = now
            }
        } else {
            NotificationPreferences.update({ NotificationPreferences.userId eq userId }) {
                request.enableNotifications?.let { value -> it[NotificationPreferences.enableNotifications] = value }
                request.soundEnabled?.let { value -> it[NotificationPreferences.soundEnabled] = value }
                request.previewEnabled?.let { value -> it[NotificationPreferences.previewEnabled] = value }
                request.ringtoneEnabled?.let { value -> it[NotificationPreferences.ringtoneEnabled] = value }
                it[NotificationPreferences.dndStartHour] = effStartMinute / 60
                it[NotificationPreferences.dndEndHour] = effEndMinute / 60
                request.dndEnabled?.let { value -> it[NotificationPreferences.dndEnabled] = value }
                it[NotificationPreferences.dndStartMinute] = effStartMinute
                it[NotificationPreferences.dndEndMinute] = effEndMinute
                it[NotificationPreferences.updatedAt] = now
            }
        }

        NotificationPreferences.selectAll()
            .where { NotificationPreferences.userId eq userId }
            .first()
            .toResponse()
    }

    private fun ResultRow.toResponse(): NotificationSettingsResponse {
        val startMinute = this[NotificationPreferences.dndStartMinute].coerceIn(0, 1439)
        val endMinute = this[NotificationPreferences.dndEndMinute].coerceIn(0, 1439)
        return NotificationSettingsResponse(
            enableNotifications = this[NotificationPreferences.enableNotifications],
            soundEnabled = this[NotificationPreferences.soundEnabled],
            previewEnabled = this[NotificationPreferences.previewEnabled],
            ringtoneEnabled = this[NotificationPreferences.ringtoneEnabled],
            dndStartHour = this[NotificationPreferences.dndStartHour].coerceIn(0, 23),
            dndEndHour = this[NotificationPreferences.dndEndHour].coerceIn(0, 23),
            dndEnabled = this[NotificationPreferences.dndEnabled],
            dndStartMinute = startMinute,
            dndEndMinute = endMinute,
            updatedAt = this[NotificationPreferences.updatedAt]
        )
    }
}
