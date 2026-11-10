package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.core.model.ChatSettingsResponse
import com.maodouchat.core.model.UpdateChatSettingsRequest
import com.maodouchat.data.repository.BotNetworkRepository
import com.maodouchat.data.repository.ChatNetworkRepository
import com.maodouchat.data.repository.GroupNetworkRepository
import com.maodouchat.data.repository.PinStarNetworkRepository
import com.maodouchat.domain.conversation.ChatSettingsGateway
import com.maodouchat.domain.conversation.PinStarGateway
import com.maodouchat.domain.conversation.PinStarMessage
import com.maodouchat.domain.groups.BotGroupActionGateway
import com.maodouchat.domain.groups.BotGroupActionMessage
import com.maodouchat.domain.messaging.ChatSenderMessage

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

/** 机器人群操作网络端口的 app 侧实现：直通 GroupNetworkRepository / BotNetworkRepository。 */
internal class BotGroupActionNetworkGateway : BotGroupActionGateway {
    private val groupRepository = GroupNetworkRepository()
    private val botRepository = BotNetworkRepository()

    override suspend fun allSearchableUsers(token: String) =
        groupRepository.allSearchableUsers(token)

    override suspend fun renameGroup(token: String, chatId: String, name: String) =
        groupRepository.rename(token, chatId, name)

    override suspend fun addMembers(token: String, chatId: String, userIds: List<String>) =
        groupRepository.addMembers(token, chatId, userIds)

    override suspend fun removeMember(token: String, chatId: String, userId: String) =
        groupRepository.removeMember(token, chatId, userId)

    override suspend fun postBotInbox(token: String, chatId: String, plaintext: String) =
        botRepository.postInbox(token, chatId, plaintext)

    override suspend fun chatCommands(token: String, chatId: String) =
        botRepository.chatCommands(token, chatId)

    override suspend fun listBots(token: String) = botRepository.listBots(token)

    override suspend fun inviteBotToChat(token: String, chatId: String, botId: String) =
        botRepository.inviteToChat(token, chatId, botId)
}

/** domain 侧 BotGroupActionMessage 到 app 字符串资源的映射。 */
internal fun botGroupActionMessageResId(message: BotGroupActionMessage): Int = when (message) {
    BotGroupActionMessage.SESSION_EXPIRED -> R.string.error_session_expired
    BotGroupActionMessage.CONTACTS_LOAD_FAILED -> R.string.contacts_load_failed
    BotGroupActionMessage.GROUP_NAME_LENGTH -> R.string.chat_group_name_length
    BotGroupActionMessage.GROUP_NAME_UPDATED -> R.string.chat_group_name_updated
    BotGroupActionMessage.GROUP_NAME_FAILED -> R.string.chat_group_name_failed
    BotGroupActionMessage.GROUP_MEMBER_ADDED_KEY -> R.string.chat_group_member_added_key
    BotGroupActionMessage.GROUP_MEMBER_REMOVED_KEY -> R.string.chat_group_member_removed_key
    BotGroupActionMessage.GROUP_ADD_FAILED -> R.string.chat_group_add_failed
    BotGroupActionMessage.GROUP_REMOVE_FAILED -> R.string.chat_group_remove_failed
    BotGroupActionMessage.BOT_INVITE_FAILED -> R.string.group_play_bot_invite_failed
    BotGroupActionMessage.BOT_INVITED -> R.string.group_play_bot_invited
    BotGroupActionMessage.GROUP_FALLBACK_NAME -> R.string.chat_group
    BotGroupActionMessage.CHAT_MEMBERS_COUNT -> R.plurals.chat_members_count
}

/** domain 侧 ChatSenderMessage 到 app 字符串资源的映射。 */
internal fun chatSenderMessageResId(message: ChatSenderMessage): Int = when (message) {
    ChatSenderMessage.SECRET_CHAT_FORWARD_BLOCKED -> R.string.secret_chat_forward_blocked
    ChatSenderMessage.NUDGE_DISABLED -> R.string.nudge_disabled
    ChatSenderMessage.CHAT_WS_SEND_FAILED -> R.string.chat_ws_send_failed
    ChatSenderMessage.ERROR_SESSION_EXPIRED -> R.string.error_session_expired
    ChatSenderMessage.CHAT_OTHER_PERSON -> R.string.chat_other_person
    ChatSenderMessage.CHAT_NUDGE_YOU_NUDGED -> R.string.chat_nudge_you_nudged
    ChatSenderMessage.CHAT_SEND_FAILED -> R.string.chat_send_failed
}
