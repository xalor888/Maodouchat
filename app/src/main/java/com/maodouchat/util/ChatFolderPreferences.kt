package com.maodouchat.util

import android.content.Context
import androidx.core.content.edit
import com.maodouchat.core.model.ChatFolder
import com.maodouchat.domain.conversation.ChatFolderJsonPolicy

/**
 * 会话文件夹本地缓存：按账号隔离；变更时由 ChatListViewModel 同步到 `/api/chat-folders`。
 */
object ChatFolderPreferences {
    private const val PREFS_NAME = "chat_folder_prefs"
    private const val KEY_FOLDERS = "folders"

    fun getFolders(context: Context): List<ChatFolder> {
        val userId = currentUserId(context) ?: return emptyList()
        val raw = userScopedPrefs(context, PREFS_NAME).getString(userScopedKey(KEY_FOLDERS, userId), null) ?: return emptyList()
        return ChatFolderJsonPolicy.decodeFolders(raw)
    }

    fun setFolders(context: Context, folders: List<ChatFolder>) {
        val userId = currentUserId(context) ?: return
        userScopedPrefs(context, PREFS_NAME).edit {
            putString(userScopedKey(KEY_FOLDERS, userId), ChatFolderJsonPolicy.encodeFolders(folders))
        }
    }

    fun clearForUser(context: Context, userId: String) {
        if (userId.isBlank()) return
        userScopedPrefs(context, PREFS_NAME).edit { remove(userScopedKey(KEY_FOLDERS, userId)) }
    }

}
