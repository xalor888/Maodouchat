package com.maodouchat.data.repository

import com.maodouchat.network.ApiService
import com.maodouchat.network.CurrentUserPublicResponse
import com.maodouchat.network.UserDto

/**
 * 需要鉴权的用户资料读取（G328c）。
 *
 * 为什么单独一个仓库：`ui/` 直接调 `ApiService.getUser/getUsers/getCurrentUser*` 这件事
 * 被棘轮盯着（`ClientArchitectureTest.frozenUiApiCallers`）。这些调用点的归宿不是
 * `data/repository/UserRepository`——那个是**本地 Room** 仓库，把网络塞进去会让
 * 「本地读写」与「远端拉取」两种失败语义混在一起（前者永不为空、后者要处理网络错误）。
 *
 * 因此这里只做一件事：把「远端用户资料」这组端点收成一个 data 层入口。
 * 刻意很薄（不缓存、不重试）——调用方（二维码页、通话页、实时联系人刷新）各自有
 * 自己的会话校验与容错，加策略只会让「谁负责」变模糊。构造器接受 lambda，
 * 因此本类自身可测。
 */
internal class UserNetworkRepository(
    private val fetchUser: suspend (String, String) -> Result<UserDto> = { token, id -> ApiService.getUser(token, id) },
    private val fetchUsers: suspend (String, Int, Int) -> Result<List<UserDto>> = { token, limit, offset ->
        ApiService.getUsers(token, limit, offset)
    },
    private val fetchCurrentUser: suspend (String) -> Result<UserDto> = { token -> ApiService.getCurrentUser(token) },
    private val fetchCurrentUserPublic: suspend (String) -> Result<CurrentUserPublicResponse> = { token ->
        ApiService.getCurrentUserPublic(token)
    },
) {
    /** 联系人/自己：按 id 取单个用户（实时在线状态刷新等）。 */
    suspend fun user(token: String? = null, userId: String): Result<UserDto> =
        fetchUser(token ?: currentAccessToken(), userId)

    /** 全量用户列表（通话页把 userId 映射成名字）。 */
    suspend fun users(token: String, limit: Int = 30, offset: Int = 0): Result<List<UserDto>> =
        fetchUsers(token, limit, offset)

    /** 当前登录账号（二维码页要拿自己的 id/name 生成名片）。 */
    suspend fun currentUser(token: String? = null): Result<UserDto> =
        fetchCurrentUser(token ?: currentAccessToken())

    /** 当前账号的公开投影（设置页）。 */
    suspend fun currentUserPublic(token: String): Result<CurrentUserPublicResponse> =
        fetchCurrentUserPublic(token)
}
