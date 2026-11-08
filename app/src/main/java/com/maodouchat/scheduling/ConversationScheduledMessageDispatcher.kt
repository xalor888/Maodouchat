package com.maodouchat.scheduling

import com.maodouchat.domain.messaging.DefaultConversationCommandFacade
import com.maodouchat.core.model.ConversationCommandRejection
import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ConversationCommandOutcome
import com.maodouchat.core.model.ScheduledMessage
/**
 * Scheduling-domain adapter for turning a due item into a durable v2 message.
 *
 * The Worker remains responsible for retry and recurrence bookkeeping; it delegates message
 * staging to [DefaultConversationCommandFacade] through this adapter rather than duplicating the
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
