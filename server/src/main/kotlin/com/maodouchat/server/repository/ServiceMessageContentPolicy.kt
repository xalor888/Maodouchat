package com.maodouchat.server.repository

private val INLINE_META_PATTERN = Regex("<meta>.*?</meta>", RegexOption.DOT_MATCHES_ALL)

/** Removes client-rendered metadata from content copied into a server-authored message. */
internal fun stripInlineMeta(content: String): String =
    INLINE_META_PATTERN.replace(content, "")
        .replace("</meta>", "")
        .replace("<meta>", "")
        .trim()

/** Preserves the final server-authored metadata block while removing embedded blocks. */
internal fun stripInlineMetaPreservingTrailing(content: String): String {
    val trailingMeta = INLINE_META_PATTERN.findAll(content).lastOrNull()?.value
    val body = INLINE_META_PATTERN.replace(content, "")
        .replace("</meta>", "")
        .replace("<meta>", "")
        .trim()
    return if (trailingMeta == null || trailingMeta in body) body else body + trailingMeta
}
