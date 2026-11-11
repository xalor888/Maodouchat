package com.maodouchat.ui.screen.chatdetail

import android.text.format.DateUtils
import com.maodouchat.R
import com.maodouchat.core.model.ChatSettingsResponse
import com.maodouchat.core.model.UpdateChatSettingsRequest
import com.maodouchat.data.repository.AccountSecurityNetworkRepository
import com.maodouchat.data.repository.BotNetworkRepository
import com.maodouchat.data.repository.ChatNetworkRepository
import com.maodouchat.data.repository.GroupNetworkRepository
import com.maodouchat.data.repository.ModerationNetworkRepository
import com.maodouchat.data.repository.PinStarNetworkRepository
import com.maodouchat.domain.conversation.ChatModerationGateway
import com.maodouchat.domain.conversation.ChatModerationMessage
import com.maodouchat.domain.conversation.ChatReminderMessage
import com.maodouchat.domain.conversation.ChatSettingsGateway
import com.maodouchat.domain.conversation.PinStarGateway
import com.maodouchat.domain.conversation.PinStarMessage
import com.maodouchat.domain.groups.BotGroupActionGateway
import com.maodouchat.domain.groups.BotGroupActionMessage
import com.maodouchat.domain.messaging.ChatSenderMessage
import com.maodouchat.domain.messaging.ScheduledMessageText
import com.maodouchat.domain.messaging.ChatOutgoingHydrationText

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

/** 拉黑/举报网络端口的 app 侧实现：直通 ModerationNetworkRepository / AccountSecurityNetworkRepository。 */
internal class ChatModerationNetworkGateway : ChatModerationGateway {
    private val moderationRepository = ModerationNetworkRepository()
    private val accountSecurityRepository = AccountSecurityNetworkRepository()

    override suspend fun blockedUserIds(token: String) =
        moderationRepository.blockedUserIds(token)

    override suspend fun blockUser(token: String, userId: String) =
        moderationRepository.blockUser(token, userId)

    override suspend fun unblockUser(token: String, userId: String) =
        accountSecurityRepository.unblock(token, userId)

    override suspend fun createReport(
        token: String,
        targetType: String,
        targetId: String,
        chatId: String?,
        messageId: String?,
        reason: String,
        description: String?,
    ): Result<Unit> =
        moderationRepository.createReport(token, targetType, targetId, chatId, messageId, reason, description).map { }
}

/** domain 侧 ChatReminderMessage 到 app 字符串资源的映射。 */
internal fun chatReminderMessageResId(message: ChatReminderMessage): Int = when (message) {
    ChatReminderMessage.REMINDER_SCHEDULED -> R.string.message_reminder_scheduled
    ChatReminderMessage.SESSION_EXPIRED -> R.string.error_session_expired
    ChatReminderMessage.SCHEDULE_FAILED -> R.string.schedule_failed
}

/** domain 侧 ScheduledMessageText 到 app 字符串资源的映射。 */
internal fun scheduledMessageResId(message: ScheduledMessageText): Int = when (message) {
    ScheduledMessageText.QUEUED_AT -> R.string.schedule_queued_at
    ScheduledMessageText.CANCELLED -> R.string.schedule_cancelled
    ScheduledMessageText.FAILED -> R.string.schedule_failed
    ScheduledMessageText.SENT_NOW -> R.string.schedule_sent_now
    ScheduledMessageText.CANCELLED_ALL -> R.string.schedule_cancelled_all
    ScheduledMessageText.RESCHEDULED -> R.string.schedule_rescheduled
    ScheduledMessageText.DISABLED -> R.string.scheduled_messages_disabled
    ScheduledMessageText.SESSION_EXPIRED -> R.string.error_session_expired
    ScheduledMessageText.BLOCKED -> R.string.chat_blocked_user_status
    ScheduledMessageText.LIMIT_REACHED -> R.string.schedule_limit_reached
}

/** domain 侧 ChatOutgoingHydrationText 到 app 字符串资源的映射。 */
internal fun chatOutgoingHydrationTextResId(message: ChatOutgoingHydrationText): Int = when (message) {
    ChatOutgoingHydrationText.RECIPIENT_NOT_READY -> R.string.chat_recipient_not_ready
    ChatOutgoingHydrationText.GROUP_NAME -> R.string.chat_group
    ChatOutgoingHydrationText.MEMBERS_COUNT -> R.plurals.chat_members_count
}

/** domain 侧 ChatModerationMessage 到 app 字符串资源的映射。 */
internal fun chatModerationMessageResId(message: ChatModerationMessage): Int = when (message) {
    ChatModerationMessage.FEATURE_DISABLED_BY_ADMIN -> R.string.feature_disabled_by_admin
    ChatModerationMessage.SESSION_EXPIRED -> R.string.error_session_expired
    ChatModerationMessage.CHAT_BLOCKED_USER_STATUS -> R.string.chat_blocked_user_status
    ChatModerationMessage.CHAT_BLOCK_FAILED -> R.string.chat_block_failed
    ChatModerationMessage.CHAT_UNBLOCKED_USER_STATUS -> R.string.chat_unblocked_user_status
    ChatModerationMessage.CHAT_UNBLOCK_FAILED -> R.string.chat_unblock_failed
    ChatModerationMessage.CHAT_REPORT_SUBMITTED -> R.string.chat_report_submitted
    ChatModerationMessage.CHAT_REPORT_FAILED -> R.string.chat_report_failed
}

/** 提醒文案里的相对时间：app 侧用 DateUtils 实现，domain 侧只拿字符串。 */
internal fun reminderRelativeTimeText(remindAtMillis: Long, nowMillis: Long): String =
    DateUtils.getRelativeTimeSpanString(remindAtMillis, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString()

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
