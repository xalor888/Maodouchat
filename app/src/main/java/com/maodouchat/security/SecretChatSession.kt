package com.maodouchat.security

import android.content.Context
import com.maodouchat.util.MediaCache
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-scoped set of secret-chat ids currently on a content surface.
 * [MainActivity] ORs this into FLAG_SECURE even when the global screen-secure switch is off.
 */
object SecretChatSession {
    private val activeSurfaceChatIds = CopyOnWriteArraySet<String>()

    fun markSurfaceActive(chatId: String) {
        if (chatId.isBlank()) return
        activeSurfaceChatIds.add(chatId)
    }

    fun markSurfaceInactive(chatId: String, context: Context? = null) {
        if (chatId.isBlank()) return
        activeSurfaceChatIds.remove(chatId)
        if (context != null) MediaCache.deleteSecretChatMedia(context, chatId)
    }

    fun clearAllSurfaces(context: Context? = null) {
        if (context != null) {
            activeSurfaceChatIds.toList().forEach { chatId ->
                MediaCache.deleteSecretChatMedia(context, chatId)
                // 清理该密聊会话的搜索索引（存量残留 + SIM 变更紧急清除）
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        com.maodouchat.MaodouchatApp.instance.database.messageSearchDao().deleteChatIndex(chatId)
                    }
                }
            }
        }
        activeSurfaceChatIds.clear()
    }

    /**
     * 8.42：仅清空 surface 标记，不删磁盘/索引。
     * 用于导航路由变化（返回栈中其它密聊会话的媒体/索引应保留，避免重复下载/重新索引）；
     * 真正的磁盘清除由 [markSurfaceInactive]（离开单 surface）、[clearAllSurfaces]
     * （登出/SIM 变更/密聊禁用）承担。
     */
    fun clearSurfaceMarkers() {
        activeSurfaceChatIds.clear()
    }

    fun hasActiveSecretSurface(): Boolean = activeSurfaceChatIds.isNotEmpty()

    fun activeSecretSurfaceChatIds(): Set<String> = activeSurfaceChatIds.toSet()
}
