package com.maodouchat.security

import android.content.Context
import com.maodouchat.domain.conversation.SecretChatSurfacePolicy
import com.maodouchat.util.MediaCache

// 进程级密聊 surface id 集合：纯记账已迁入 :domain:conversation 的
// SecretChatSurfacePolicy，这里只留带 Context 的磁盘/索引清除。
object SecretChatSession {

    fun markSurfaceActive(chatId: String) =
        SecretChatSurfacePolicy.markSurfaceActive(chatId)

    fun markSurfaceInactive(chatId: String, context: Context? = null) {
        if (chatId.isBlank()) return
        SecretChatSurfacePolicy.clearSurfaceMarker(chatId)
        if (context != null) MediaCache.deleteSecretChatMedia(context, chatId)
    }

    fun clearAllSurfaces(context: Context? = null) {
        if (context != null) {
            SecretChatSurfacePolicy.activeSecretSurfaceChatIds().forEach { chatId ->
                MediaCache.deleteSecretChatMedia(context, chatId)
                // 清理该密聊会话的搜索索引（存量残留 + SIM 变更紧急清除）
                runCatching {
                    kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                        com.maodouchat.MaodouchatApp.instance.database.messageSearchDao().deleteChatIndex(chatId)
                    }
                }
            }
        }
        SecretChatSurfacePolicy.clearSurfaceMarkers()
    }

    fun clearSurfaceMarkers() = SecretChatSurfacePolicy.clearSurfaceMarkers()

    fun clearSurfaceMarker(chatId: String) =
        SecretChatSurfacePolicy.clearSurfaceMarker(chatId)

    fun clearSurfaceMarkersExcept(keepChatId: String?) =
        SecretChatSurfacePolicy.clearSurfaceMarkersExcept(keepChatId)

    fun hasActiveSecretSurface(): Boolean =
        SecretChatSurfacePolicy.hasActiveSecretSurface()

    fun activeSecretSurfaceChatIds(): Set<String> =
        SecretChatSurfacePolicy.activeSecretSurfaceChatIds()
}
