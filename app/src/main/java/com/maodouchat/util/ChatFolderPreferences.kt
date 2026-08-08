package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话文件夹本地缓存：按账号隔离；变更时由 ChatListViewModel 同步到 `/api/chat-folders`。
 */
object ChatFolderPreferences {
    private const val PREFS_NAME = "chat_folder_prefs"
    private const val KEY_FOLDERS = "folders"

    fun getFolders(context: Context): List<ChatFolder> {
        val userId = currentUserId(context) ?: return emptyList()
        val raw = prefs(context).getString(key(KEY_FOLDERS, userId), null) ?: return emptyList()
        return decodeFolders(raw)
    }

    fun setFolders(context: Context, folders: List<ChatFolder>) {
        val userId = currentUserId(context) ?: return
        prefs(context).edit()
            .putString(key(KEY_FOLDERS, userId), encodeFolders(folders))
            .apply()
    }

    fun clearForUser(context: Context, userId: String) {
        if (userId.isBlank()) return
        prefs(context).edit().remove(key(KEY_FOLDERS, userId)).apply()
    }

    private fun currentUserId(context: Context): String? =
        TokenManager.getInstance(context.applicationContext).getUserId()?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(prefix: String, userId: String): String = "${prefix}_$userId"

    private fun encodeFolders(folders: List<ChatFolder>): String {
        val arr = JSONArray()
        folders.forEach { folder ->
            arr.put(
                JSONObject()
                    .put("id", folder.id)
                    .put("name", folder.name)
                    .put("sortOrder", folder.sortOrder)
                    .put("chatIds", JSONArray(folder.chatIds))
            )
        }
        return arr.toString()
    }

    private fun decodeFolders(raw: String): List<ChatFolder> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val name = ChatFolderPolicy.normalizeName(obj.optString("name"))
                if (id.isEmpty() || name.isEmpty()) continue
                val chatIdsArr = obj.optJSONArray("chatIds")
                val chatIds = buildList {
                    if (chatIdsArr != null) {
                        for (j in 0 until chatIdsArr.length()) {
                            val cid = chatIdsArr.optString(j, "").trim()
                            if (cid.isNotEmpty()) add(cid)
                        }
                    }
                }.distinct()
                add(
                    ChatFolder(
                        id = id,
                        name = name,
                        chatIds = chatIds,
                        sortOrder = obj.optInt("sortOrder", i)
                    )
                )
            }
        }.sortedBy { it.sortOrder }
    }.getOrDefault(emptyList())
}
