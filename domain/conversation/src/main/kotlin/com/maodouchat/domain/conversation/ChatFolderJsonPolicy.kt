package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatFolder
import org.json.JSONArray
import org.json.JSONObject

// 会话文件夹本地缓存的 JSON 编解码：List<ChatFolder> <-> 字符串。
object ChatFolderJsonPolicy {
    fun encodeFolders(folders: List<ChatFolder>): String {
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

    fun decodeFolders(raw: String): List<ChatFolder> = runCatching {
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
