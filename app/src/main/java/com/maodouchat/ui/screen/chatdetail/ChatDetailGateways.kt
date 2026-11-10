package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.core.model.ChatSettingsResponse
import com.maodouchat.core.model.UpdateChatSettingsRequest
import com.maodouchat.data.repository.ChatNetworkRepository
import com.maodouchat.data.repository.PinStarNetworkRepository
import com.maodouchat.domain.conversation.ChatSettingsGateway
import com.maodouchat.domain.conversation.PinStarGateway
import com.maodouchat.domain.conversation.PinStarMessage

/** 置顶/星标网络端口的 app 侧实现：直通 PinStarNetworkRepository。 */
internal class PinStarNetworkGateway : PinStarGateway {
    private val repository = PinStarNetworkRepository()

    override suspend fun pinned(token: String, chatId: String) =
        repository.pinned(token, chatId)

    override suspend fun togglePin(token: String, chatId: String, messageId: String) =
        repository.togglePin(token, chatId, messageId)

    override suspend fun toggleStar(token: String, messageId: String) =
        repository.toggleStar(token, messageId)
}

/** 会话级设置网络端口的 app 侧实现：直通 ChatNetworkRepository。 */
internal class ChatSettingsNetworkGateway : ChatSettingsGateway {
    private val repository = ChatNetworkRepository()

    override suspend fun updateChatSettings(
        token: String,
        chatId: String,
        request: UpdateChatSettingsRequest,
    ): Result<ChatSettingsResponse> = repository.updateChatSettings(token, chatId, request)
}

/** domain 侧 PinStarMessage 到 app 字符串资源的映射。 */
internal fun pinStarMessageResId(message: PinStarMessage): Int = when (message) {
    PinStarMessage.FEATURE_DISABLED_BY_ADMIN -> R.string.feature_disabled_by_admin
    PinStarMessage.SESSION_EXPIRED -> R.string.error_session_expired
    PinStarMessage.PIN_FORBIDDEN -> R.string.chat_pin_forbidden
    PinStarMessage.PIN_LIMIT -> R.string.chat_pin_limit
    PinStarMessage.PIN_SUCCESS -> R.string.chat_pin_success
    PinStarMessage.UNPIN_SUCCESS -> R.string.chat_unpin_success
    PinStarMessage.PIN_FAILED -> R.string.chat_pin_failed
    PinStarMessage.BATCH_PIN_PARTIAL -> R.string.chat_batch_pin_partial
    PinStarMessage.BATCH_UNPIN_PARTIAL -> R.string.chat_batch_unpin_partial
    PinStarMessage.BATCH_PIN_SUCCESS -> R.string.chat_batch_pin_success
    PinStarMessage.BATCH_UNPIN_SUCCESS -> R.string.chat_batch_unpin_success
    PinStarMessage.STARRING_DISABLED -> R.string.message_starring_disabled
    PinStarMessage.SECRET_STAR_BLOCKED -> R.string.secret_star_blocked
    PinStarMessage.STARRED -> R.string.chat_starred_status
    PinStarMessage.UNSTARRED -> R.string.chat_unstarred_status
    PinStarMessage.STAR_FAILED -> R.string.chat_star_failed
}
