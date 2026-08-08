package com.maodouchat.security

import android.content.Context
import com.maodouchat.util.MediaCache
import com.maodouchat.util.SecretAutoDestroyPrefs

/**
 * 密聊会话 TTL 兜底（B2 surface · 自动销毁，health 名 ttlz）。
 *
 * 与「单条消息 24h 阅后即焚」互补：这是「会话级」无活动 TTL。
 * 开启 [SecretAutoDestroyPrefs] 后，密聊会话在 [SecretAutoDestroyPrefs.ttlSeconds]
 * 内无活动即整体销毁：本地解密缓存媒体 + 会话快照标记清除。
 *
 * 纯本地逻辑：只销毁本机解密缓存，服务端只有密文、不接触密聊明文。
 */
object SecretSessionTtl {

    /**
     * 计算剩余存活秒数（基于最后活动时间）。
     * @return 剩余秒数；<=0 表示已过期；未开启自动销毁或最后活动未知时返回 [Long.MAX_VALUE]（不销毁）。
     */
    fun remainingSeconds(context: Context, chatId: String, lastActivityAt: Long): Long {
        if (chatId.isBlank()) return Long.MAX_VALUE
        if (!SecretAutoDestroyPrefs.isEnabled(context)) return Long.MAX_VALUE
        if (lastActivityAt <= 0L) return Long.MAX_VALUE
        val ttl = SecretAutoDestroyPrefs.ttlSeconds(context)
        val expiredAt = lastActivityAt + ttl * 1000L
        val remainingMs = expiredAt - System.currentTimeMillis()
        return remainingMs / 1000L
    }

    fun isExpired(context: Context, chatId: String, lastActivityAt: Long): Boolean =
        remainingSeconds(context, chatId, lastActivityAt) <= 0L

    /**
     * 销毁单个密聊会话：清除本地解密缓存。返回是否执行了清理。
     * 由接入方在（a）TTL 到期扫描、（b）进入会话前校验 时调用。
     */
    fun destroySession(context: Context, chatId: String): Boolean {
        if (chatId.isBlank()) return false
        if (!SecretAutoDestroyPrefs.isEnabled(context)) return false
        MediaCache.deleteSecretChatMedia(context, chatId)
        // 清理该会话的搜索索引（存量密聊消息可能在索引过滤启用前已写入）
        runCatching {
            kotlinx.coroutines.runBlocking {
                com.maodouchat.MaodouchatApp.instance.database.messageSearchDao().deleteChatIndex(chatId)
            }
        }
        return true
    }

    /**
     * 扫描一批密聊会话，销毁已过期者，返回被销毁的会话 id 列表。
     * @param lastActivityAt 每个 chatId 的最后活动时间（由接入方的会话表提供）。
     */
    fun sweepExpired(context: Context, lastActivityAtByChat: Map<String, Long>): List<String> {
        if (!SecretAutoDestroyPrefs.isEnabled(context)) return emptyList()
        return lastActivityAtByChat.entries
            .filter { (chatId, lastAt) -> isExpired(context, chatId, lastAt) }
            .map { (chatId, _) ->
                destroySession(context, chatId)
                chatId
            }
    }
}
