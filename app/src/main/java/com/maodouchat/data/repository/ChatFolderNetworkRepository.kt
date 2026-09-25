package com.maodouchat.data.repository

import com.maodouchat.network.ApiService
import com.maodouchat.network.ChatFolderDto
import com.maodouchat.network.ChatFoldersSyncResponse

/**
 * 会话分组（文件夹）的**远端**调用（G328c）。
 *
 * 只有一读一写，但两者必须成对出现：`putChatFolders` 传的是**整份列表**
 * （服务端按它做全量替换，不是增量补丁），所以调用方必须先把远端那份读下来、
 * 在本地改完再整体写回。合成一个仓库就是为了让这个约束看得见。
 *
 * 与 `data/local` 的文件夹存储分开：本地是离线可用的真源，本类只是与服务器对账。
 * 刻意很薄（不缓存、不重试）。
 */
internal class ChatFolderNetworkRepository(
    private val foldersApi: suspend (String) -> Result<ChatFoldersSyncResponse> =
        { token -> ApiService.getChatFolders(token) },
    private val putFoldersApi: suspend (String, List<ChatFolderDto>) -> Result<ChatFoldersSyncResponse> =
        { token, folders -> ApiService.putChatFolders(token, folders) },
) {
    suspend fun folders(token: String): Result<ChatFoldersSyncResponse> = foldersApi(token)

    /** **全量替换**语义：`folders` 里没有的会被服务端删掉。 */
    suspend fun putFolders(token: String, folders: List<ChatFolderDto>): Result<ChatFoldersSyncResponse> =
        putFoldersApi(token, folders)
}
