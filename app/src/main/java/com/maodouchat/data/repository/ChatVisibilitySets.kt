package com.maodouchat.data.repository

import com.maodouchat.MaodouchatApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「需要对搜索结果隐藏」的会话 id 集合（U02 延伸：自 `ui/screen/chatlist/GlobalSearchScreen`
 * 的 `app.database` 直连收口）。
 *
 * 语义与调用点原实现逐字一致：**PIN 锁定 ∪ 本地密聊**，在 IO 线程读两张表后合并。
 * 与 `ChatListPorts` 的 `listLockedChatIds`/`listSecretChatIds` 是同一份数据的不同消费面
 * （那边逐个暴露给列表投影，这边给搜索一处合并集合）——刻意保持两个入口都只读 DAO，
 * 不引入第三份「真相源」。
 */
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
