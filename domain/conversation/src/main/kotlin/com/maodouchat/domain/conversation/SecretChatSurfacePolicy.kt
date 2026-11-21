package com.maodouchat.domain.conversation

import java.util.concurrent.CopyOnWriteArraySet

// 进程级密聊 surface id 纯集合：由 app 侧 SecretChatSession 平移；
// MediaCache/搜索索引清除仍在 app 侧薄壳，这里只留集合记账。
object SecretChatSurfacePolicy {
    private val activeSurfaceChatIds = CopyOnWriteArraySet<String>()

    fun markSurfaceActive(chatId: String) {
        if (chatId.isBlank()) return
        activeSurfaceChatIds.add(chatId)
    }

    // 只清标记，不删磁盘/索引（导航路由变化时用；磁盘清除由 app 侧承担）。
    fun clearSurfaceMarkers() {
        activeSurfaceChatIds.clear()
    }

    fun clearSurfaceMarker(chatId: String) {
        if (chatId.isBlank()) return
        activeSurfaceChatIds.remove(chatId)
    }

    // 清掉陈旧标记但保留 keepChatId：并发的 ChatDetail 通知不能被父路由查找误删。
    fun clearSurfaceMarkersExcept(keepChatId: String?) {
        val keep = keepChatId?.takeIf { it.isNotBlank() }
        if (keep == null) {
            activeSurfaceChatIds.clear()
            return
        }
        val stale = activeSurfaceChatIds.filter { it != keep }
        stale.forEach { activeSurfaceChatIds.remove(it) }
    }

    fun hasActiveSecretSurface(): Boolean = activeSurfaceChatIds.isNotEmpty()

    fun activeSecretSurfaceChatIds(): Set<String> = activeSurfaceChatIds.toSet()
}
