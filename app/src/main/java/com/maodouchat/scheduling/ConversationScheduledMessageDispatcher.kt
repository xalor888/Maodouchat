package com.maodouchat.scheduling

import com.maodouchat.conversation.ConversationCommandFacade
import com.maodouchat.conversation.ConversationCommandOutcome
import com.maodouchat.conversation.ConversationCommandRejection
import com.maodouchat.data.model.Chat
import com.maodouchat.util.ScheduledMessage

/**
 * Scheduling-domain adapter for turning a due item into a durable v2 message.
 *
 * The Worker remains responsible for retry and recurrence bookkeeping; it delegates message
 * staging to [ConversationCommandFacade] through this adapter rather than duplicating the
 * message/outbox transaction.
 */
class ConversationScheduledMessageDispatcher(
    private val facade: ConversationCommandFacade,
    private val resolveChat: suspend (chatId: String) -> Chat?,
) {
    suspend fun stage(
        item: ScheduledMessage,
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
