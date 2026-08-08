package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.ai.AiPromptSafetyPolicy
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.network.AiContextMessage

internal data class AiContextSenders(
    val currentUserId: String,
    val currentUserLabel: String,
    val fallbackLabel: String,
    val namesByUserId: Map<String, String>
)

internal fun buildPlainAiContextMessages(
    messages: List<Message>,
    senders: AiContextSenders,
    limit: Int,
    maxTextLength: Int = 1_000
): List<AiContextMessage> = messages.asSequence()
    .filter { it.type == MessageType.TEXT || it.type == MessageType.MARKDOWN }
    .filterNot { it.parsedMeta().aiAssisted }
    .mapNotNull { message ->
        val content = AiPromptSafetyPolicy.sanitizeContextText(
            message.parsedContent(),
            maxTextLength.coerceIn(1, 4_000)
        ).takeIf(String::isNotBlank) ?: return@mapNotNull null
        message to content
    }
    .sortedWith(compareBy<Pair<Message, String>> { it.first.timestamp }.thenBy { it.first.id })
    .toList()
    .takeLast(limit.coerceIn(1, 60))
    .map { (message, content) ->
        AiContextMessage(
            sender = AiPromptSafetyPolicy.sanitizeSender(senders.labelFor(message.senderId)),
            text = content
        )
    }

internal fun buildSummaryAiContextMessages(
    messages: List<Message>,
    senders: AiContextSenders,
    limit: Int = MAX_AI_SUMMARY_MESSAGES,
    maxTextLength: Int = 1_500
): List<AiContextMessage> = messages.asSequence()
    .filterNot { it.parsedMeta().aiAssisted }
    .mapNotNull { message ->
        val content = message.aiSummaryContextText(maxTextLength).takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        message to content
    }
    .sortedWith(compareBy<Pair<Message, String>> { it.first.timestamp }.thenBy { it.first.id })
    .toList()
    .takeLast(limit.coerceIn(1, MAX_AI_SUMMARY_MESSAGES))
    .map { (message, content) ->
        AiContextMessage(
            sender = AiPromptSafetyPolicy.sanitizeSender(senders.labelFor(message.senderId)),
            text = content
        )
    }

internal fun Message.aiSummaryContextText(maxLength: Int = 1_500): String {
    val metadata = parsedMeta()
    val joined = buildList {
        if (type == MessageType.TEXT || type == MessageType.MARKDOWN) {
            AiPromptSafetyPolicy.sanitizeContextText(parsedContent(), maxLength)
                .takeIf(String::isNotBlank)
                ?.let(::add)
        }
        AiPromptSafetyPolicy.sanitizeContextText(metadata.voiceTranscript, maxLength)
            .takeIf(String::isNotBlank)
            ?.let(::add)
        addAll(
            metadata.translations.values.map {
                AiPromptSafetyPolicy.sanitizeContextText(it, maxLength)
            }.filter(String::isNotBlank)
        )
    }
        .distinct()
        .joinToString("\n")
    return AiPromptSafetyPolicy.sanitizeContextText(joined, maxLength.coerceIn(1, 4_000))
}

private fun AiContextSenders.labelFor(userId: String): String = when {
    userId == currentUserId -> currentUserLabel
    else -> namesByUserId[userId]?.takeIf(String::isNotBlank) ?: fallbackLabel
}.take(80)
