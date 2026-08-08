package com.maodouchat.data.model

fun Message.semanticSearchText(maxLength: Int = 700): String {
    val metadata = parsedMeta()
    return buildList {
        when (type) {
            MessageType.TEXT, MessageType.MARKDOWN ->
                parsedContent().trim().takeIf(String::isNotBlank)?.let(::add)
            MessageType.LOCATION -> {
                // Prefer human label; raw lat/lng JSON alone is not useful for keyword search.
                parsedLocation()?.label?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            }
            MessageType.NUDGE ->
                // Server-stored sender-centric body still matches keywords in either POV.
                content.trim().takeIf(String::isNotBlank)?.let(::add)
            else -> Unit
        }
        metadata.voiceTranscript?.trim()?.takeIf(String::isNotBlank)?.let(::add)
        addAll(metadata.translations.values.map(String::trim).filter(String::isNotBlank))
        addAll(metadata.aiImageAnalyses.values.map(String::trim).filter(String::isNotBlank))
        addAll(metadata.aiFileAnalyses.values.map(String::trim).filter(String::isNotBlank))
        metadata.aiFileLastQuestion?.trim()?.takeIf(String::isNotBlank)?.let(::add)
    }
        .distinct()
        .joinToString("\n")
        .take(maxLength.coerceIn(1, 10_000))
}
