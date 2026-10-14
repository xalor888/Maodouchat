package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType

internal enum class ChatSearchMode { KEYWORD, SEMANTIC }

internal enum class ChatSearchScope { ALL, TEXT, VOICE, TRANSLATION, STARRED, MEDIA, MENTIONS }

internal enum class ChatSearchWindow { ALL, TODAY, SEVEN_DAYS, THIRTY_DAYS }

internal data class ChatSearchDocument(
    val message: Message,
    val text: String?,
    val voiceTranscript: String?,
    val translations: List<String>
) {
    fun parts(scope: ChatSearchScope): List<String> = when (scope) {
        ChatSearchScope.ALL, ChatSearchScope.STARRED, ChatSearchScope.MENTIONS -> buildList {
            text?.takeIf(String::isNotBlank)?.let(::add)
            voiceTranscript?.takeIf(String::isNotBlank)?.let(::add)
            addAll(translations)
        }
        ChatSearchScope.TEXT -> listOfNotNull(text?.takeIf(String::isNotBlank))
        ChatSearchScope.VOICE -> listOfNotNull(voiceTranscript?.takeIf(String::isNotBlank))
        ChatSearchScope.TRANSLATION -> translations
        ChatSearchScope.MEDIA -> emptyList()
    }

    fun matchesMediaScope(): Boolean = when (message.type) {
        MessageType.IMAGE,
        MessageType.GIF,
        MessageType.VIDEO,
        MessageType.FILE,
        MessageType.STICKER,
        MessageType.LOCATION -> true
        else -> false
    }
}
