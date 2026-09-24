package com.maodouchat.data.repository

import com.maodouchat.network.ApiService
import com.maodouchat.network.UserDto

/**
 * 群成员与群资料编辑的**远端**调用（G328c）。
 *
 * 覆盖「群成员增删 / 改群名 / 拉全量可搜索用户（供选人）」这几个动作。
 * 与 `ModerationNetworkRepository` 一样薄（不缓存、不重试）：成员变更必须看到服务端
 * 的最新成员表，否则会出现「以为加进去了其实没有」。
 *
 * 注意 `allSearchableUsers` 是**分页拉全量**（服务端一次最多 pageSize，最多拉 maxUsers）：
 * 这里的默认值与 ApiService 一致，改动它会改变网络行为，不属于搬移该做的事。
 */
internal class GroupNetworkRepository(
    private val addMembersApi: suspend (String, String, List<String>) -> Result<Unit> =
        { token, chatId, ids -> ApiService.addGroupMembers(token, chatId, ids) },
    private val removeMemberApi: suspend (String, String, String) -> Result<Unit> =
        { token, chatId, memberId -> ApiService.removeGroupMember(token, chatId, memberId) },
    private val renameApi: suspend (String, String, String) -> Result<Unit> =
        { token, chatId, newName -> ApiService.renameGroup(token, chatId, newName) },
    private val allSearchableApi: suspend (String, Int, Int) -> Result<List<UserDto>> =
        { token, pageSize, maxUsers -> ApiService.getAllSearchableUsers(token, pageSize, maxUsers) },
) {
    suspend fun addMembers(token: String, chatId: String, memberIds: List<String>): Result<Unit> =
        addMembersApi(token, chatId, memberIds)

    suspend fun removeMember(token: String, chatId: String, memberId: String): Result<Unit> =
        removeMemberApi(token, chatId, memberId)

    suspend fun rename(token: String, chatId: String, newName: String): Result<Unit> =
        renameApi(token, chatId, newName)

    suspend fun allSearchableUsers(token: String, pageSize: Int = 100, maxUsers: Int = 1000): Result<List<UserDto>> =
        allSearchableApi(token, pageSize, maxUsers)
}
