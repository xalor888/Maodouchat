package com.maodouchat.data.repository

import com.maodouchat.network.ApiService
import com.maodouchat.network.PinnedMessagesListResponse
import com.maodouchat.network.StarMessageResponse
import com.maodouchat.network.StarredMessageRefDto
import com.maodouchat.network.TogglePinResponse

/**
 * 置顶消息与星标消息的**远端**调用（G328c）。
 *
 * 两者合一个仓库：它们是同一类「给单条消息打标记」的操作，服务端也回同一族的响应
 * （`TogglePinResponse` / `StarMessageResponse` 都带**服务端认定的新状态**）。
 * 调用方必须用响应里的状态而不是本地取反——并发点两次时本地取反会算反。
 *
 * 与 `ChatNetworkRepository` 分开：那个管会话本身（创建/改名/删除），
 * 本类管会话**内**的消息标记，失败语义也不同（消息已被撤回、会话已退出）。
 *
 * 刻意很薄（不缓存、不重试）。
 */
internal class PinStarNetworkRepository(
    private val pinnedListApi: suspend (String, String) -> Result<PinnedMessagesListResponse> =
        { token, chatId -> ApiService.getPinnedMessages(token, chatId) },
    private val togglePinApi: suspend (String, String, String) -> Result<TogglePinResponse> =
        { token, chatId, messageId -> ApiService.togglePinnedMessage(token, chatId, messageId) },
    private val starredListApi: suspend (String, String?) -> Result<List<StarredMessageRefDto>> =
        { token, chatId -> ApiService.getStarredMessages(token, chatId) },
    private val toggleStarApi: suspend (String, String) -> Result<StarMessageResponse> =
        { token, messageId -> ApiService.toggleStarMessage(token, messageId) },
) {
    suspend fun pinned(token: String, chatId: String): Result<PinnedMessagesListResponse> =
        pinnedListApi(token, chatId)

    /** 置顶/取消置顶；返回值里带服务端认定的最终状态。 */
    suspend fun togglePin(token: String, chatId: String, messageId: String): Result<TogglePinResponse> =
        togglePinApi(token, chatId, messageId)

    /** 星标列表；`chatId` 为 null = 全部会话（「我的星标」页）。 */
    suspend fun starred(token: String, chatId: String? = null): Result<List<StarredMessageRefDto>> =
        starredListApi(token, chatId)

    suspend fun toggleStar(token: String, messageId: String): Result<StarMessageResponse> =
        toggleStarApi(token, messageId)
}
