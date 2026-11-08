package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ConversationCommandOutcome
import com.maodouchat.core.model.ConversationCommandRejection
import com.maodouchat.core.model.ScheduledMessage as CoreScheduledMessage
/** 到期的定时文本经消息命令门面转成持久发件箱消息；重试与重复排期仍由 Worker 负责。 */
class ConversationScheduledMessageDispatcher(
    private val facade: DefaultConversationCommandFacade,
    private val resolveChat: suspend (chatId: String) -> Chat?,
) {
    suspend fun stage(
        item: CoreScheduledMessage,
        ownerUserId: String,
    ): ConversationCommandOutcome {
        val chat = resolveChat(item.chatId)
            ?: return ConversationCommandOutcome.Rejected(ConversationCommandRejection.CHAT_UNAVAILABLE)
        return facade.stageScheduledText(
            chat = chat,
            ownerUserId = ownerUserId,
            text = item.text,
            deterministicMessageId = "sm_${item.id.removePrefix("sch_")}",
        )
    }
}
