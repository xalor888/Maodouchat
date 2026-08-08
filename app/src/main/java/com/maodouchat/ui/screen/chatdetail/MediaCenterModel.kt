package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType

enum class MediaCenterCategory { MEDIA, FILES, VOICE, LINKS, LOCATION }

data class MediaCenterItem(
    val message: Message,
    val category: MediaCenterCategory,
    val linkUrl: String? = null
)

private val webUrlRegex = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
private val trailingUrlPunctuation = setOf('.', ',', '!', '?', ';', ':', ')', ']', '}', '。', '，', '！', '？', '；', '：')

internal fun buildMediaCenterItems(messages: List<Message>): List<MediaCenterItem> =
    messages.flatMap { message ->
        when (message.type) {
            MessageType.IMAGE, MessageType.GIF, MessageType.VIDEO, MessageType.STICKER ->
                listOf(MediaCenterItem(message, MediaCenterCategory.MEDIA))
            MessageType.FILE -> listOf(MediaCenterItem(message, MediaCenterCategory.FILES))
            MessageType.VOICE -> listOf(MediaCenterItem(message, MediaCenterCategory.VOICE))
            MessageType.LOCATION -> listOf(MediaCenterItem(message, MediaCenterCategory.LOCATION))
            MessageType.TEXT -> webUrlRegex.findAll(message.parsedContent())
                .mapNotNull { match ->
                    val url = match.value.trimEnd { it in trailingUrlPunctuation }
                        .takeIf { it.length in 8..2_048 }
                    url?.let { MediaCenterItem(message, MediaCenterCategory.LINKS, it) }
                }
                .distinctBy(MediaCenterItem::linkUrl)
                .toList()
            else -> emptyList()
        }
    }.sortedByDescending { it.message.timestamp }
