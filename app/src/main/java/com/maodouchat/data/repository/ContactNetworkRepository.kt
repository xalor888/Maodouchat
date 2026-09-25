package com.maodouchat.data.repository

import com.maodouchat.network.ApiService
import com.maodouchat.network.ChatDto
import com.maodouchat.network.FriendRequestDto
import com.maodouchat.network.GroupInvitationDto
import com.maodouchat.network.GroupInviteAcceptResponse
import com.maodouchat.network.UserDto

/**
 * 联系人（好友）与群邀请的**远端**调用（G328c）。
 *
 * 合在一起的理由是它们共享同一批失败语义，而且经常在同一条流程里前后出现：
 * 加好友 → 对方通过 → 拉群 → 对方收到群邀请 → 接受/拒绝。
 * （`getUser` / `searchUsers` 不在本类：单个用户资料归 `UserNetworkRepository`，
 * 这里只放「关系」相关的端点。）
 *
 * 注意 `joinGroupByInvite` 的入参是**邀请令牌**（不是 chatId），
 * 与 `acceptGroupInvitation` 的 `inviteId` 是两码事——两者都是 String，写反了编译不报错。
 * 前者用于「拿到链接直接进群」，后者用于「我收到的群邀请列表里点接受」。
 *
 * 刻意很薄（不缓存、不重试）：好友关系与邀请状态是服务端权威，
 * 缓存它会让「对方已撤回邀请」在本机看起来还在。
 */
internal class ContactNetworkRepository(
    private val friendsApi: suspend (String) -> Result<List<UserDto>> =
        { token -> ApiService.getFriends(token) },
    private val searchUsersApi: suspend (String, String) -> Result<List<UserDto>> =
        { token, query -> ApiService.searchUsers(token, query) },
    private val sendFriendRequestApi: suspend (String, String, String) -> Result<FriendRequestDto> =
        { token, userId, message -> ApiService.sendFriendRequest(token, userId, message) },
    private val groupInvitationsApi: suspend (String) -> Result<List<GroupInvitationDto>> =
        { token -> ApiService.getGroupInvitations(token) },
    private val acceptInviteApi: suspend (String, String) -> Result<GroupInviteAcceptResponse> =
        { token, inviteId -> ApiService.acceptGroupInvitation(token, inviteId) },
    private val declineInviteApi: suspend (String, String) -> Result<GroupInviteAcceptResponse> =
        { token, inviteId -> ApiService.declineGroupInvitation(token, inviteId) },
    private val joinByInviteApi: suspend (String, String) -> Result<ChatDto> =
        { token, inviteToken -> ApiService.joinGroupByInvite(token, inviteToken) },
) {
    suspend fun friends(token: String? = null): Result<List<UserDto>> = friendsApi(token ?: currentAccessToken())

    /** 按昵称/用户名搜人（`UserNetworkRepository.user` 是**按 id** 取单个）。 */
    suspend fun searchUsers(token: String? = null, query: String): Result<List<UserDto>> = searchUsersApi(token ?: currentAccessToken(), query)

    suspend fun sendFriendRequest(token: String? = null, userId: String, message: String = ""): Result<FriendRequestDto> =
        sendFriendRequestApi(token ?: currentAccessToken(), userId, message)

    suspend fun groupInvitations(token: String? = null): Result<List<GroupInvitationDto>> = groupInvitationsApi(token ?: currentAccessToken())

    suspend fun acceptGroupInvitation(token: String? = null, inviteId: String): Result<GroupInviteAcceptResponse> =
        acceptInviteApi(token ?: currentAccessToken(), inviteId)

    suspend fun declineGroupInvitation(token: String? = null, inviteId: String): Result<GroupInviteAcceptResponse> =
        declineInviteApi(token ?: currentAccessToken(), inviteId)

    /** 用**邀请令牌**直接进群（`inviteToken` 不是 chatId）。 */
    suspend fun joinGroupByInvite(token: String? = null, inviteToken: String): Result<ChatDto> =
        joinByInviteApi(token ?: currentAccessToken(), inviteToken)
}
