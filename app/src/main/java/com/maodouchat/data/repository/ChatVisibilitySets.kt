package com.maodouchat.data.repository

import com.maodouchat.MaodouchatApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ChatVisibilitySets {

    suspend fun redactedChatIds(): Set<String> = withContext(Dispatchers.IO) {
        val database = MaodouchatApp.instance.database
        database.chatLockDao().listLockedChatIds().toSet() +
            database.chatDao().listSecretChatIds().toSet()
    }

    /** 两个集合分开消费时的载体（星标页要分别判断「密聊域」与「锁定时」）。 */
    data class RedactionSets(val locked: Set<String>, val secret: Set<String>)

    /**
     * 分别读两组集合，**每组独立容错**（读失败回落空集、取消重抛）——与
     * `StarredMessagesScreen` 原实现逐字同语义：一侧读失败不影响另一侧。
     */
    suspend fun safeRedactionSets(): RedactionSets = RedactionSets(
        locked = try {
            MaodouchatApp.instance.database.chatLockDao().listLockedChatIds().toSet()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptySet()
        },
        secret = try {
            MaodouchatApp.instance.database.chatDao().listSecretChatIds().toSet()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptySet()
        },
    )
}
