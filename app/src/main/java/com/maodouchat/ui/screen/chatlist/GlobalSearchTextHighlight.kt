package com.maodouchat.ui.screen.chatlist

/**
 * W1-05: pure helpers for stable keyword highlighting / snippet windows in global search.
 * Keeps Compose free of scoring/snippet math so unit tests stay hermetic.
 */
data class HighlightSpan(val start: Int, val end: Int) {
    init {
        require(start >= 0 && end >= start)
    }
}

data class SearchSnippet(
    val text: String,
    /** Spans relative to [text], not the original full body. */
    val highlights: List<HighlightSpan>
)

object GlobalSearchTextHighlight {
    /**
     * Finds non-overlapping case-insensitive spans for the full query and its whitespace tokens.
     * Longer matches win; later equal-length spans keep earlier ones.
     */
    fun findHighlightSpans(text: String, query: String): List<HighlightSpan> {
        val normalizedQuery = query.trim()
        if (text.isEmpty() || normalizedQuery.isEmpty()) return emptyList()
        val tokens = buildList {
            add(normalizedQuery)
            normalizedQuery.split(WHITESPACE)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals(normalizedQuery, ignoreCase = true) }
                .forEach(::add)
        }.distinctBy { it.lowercase() }
            .sortedByDescending { it.length }

        val occupied = BooleanArray(text.length)
        val spans = mutableListOf<HighlightSpan>()
        for (token in tokens) {
            var from = 0
            while (from < text.length) {
                val index = text.indexOf(token, startIndex = from, ignoreCase = true)
                if (index < 0) break
                val end = index + token.length
                if ((index until end).none { occupied[it] }) {
                    for (i in index until end) occupied[i] = true
                    spans += HighlightSpan(index, end)
                }
                from = index + 1
            }
        }
        return spans.sortedBy { it.start }
    }

    /**
     * Builds a short snippet centered on the first highlight (or start of text).
     * Prefix/suffix ellipsis when truncated.
     */
    fun buildSnippet(
        fullText: String,
        query: String,
        maxChars: Int = DEFAULT_SNIPPET_CHARS
    ): SearchSnippet {
        val compact = fullText.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (compact.isEmpty()) return SearchSnippet("", emptyList())
        if (maxChars <= 0) return SearchSnippet(compact, findHighlightSpans(compact, query))

        val fullSpans = findHighlightSpans(compact, query)
        if (compact.length <= maxChars) {
            return SearchSnippet(compact, fullSpans)
        }

        val focus = fullSpans.firstOrNull()?.start ?: 0
        val half = maxChars / 2
        var start = (focus - half).coerceAtLeast(0)
        var end = (start + maxChars).coerceAtMost(compact.length)
        if (end - start < maxChars) {
            start = (end - maxChars).coerceAtLeast(0)
        }
        // Prefer word boundaries when possible.
        if (start > 0) {
            val space = compact.indexOf(' ', start)
            if (space in (start + 1) until end) {
                // keep mid-word cut if only space is far; no-op default
            }
            val back = compact.lastIndexOf(' ', start)
            if (back >= 0 && start - back < 12) start = back + 1
        }
        if (end < compact.length) {
            val forward = compact.indexOf(' ', end)
            if (forward > 0 && forward - end < 12) end = forward
        }

        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < compact.length) "…" else ""
        val body = compact.substring(start, end)
        val snippetText = prefix + body + suffix
        val mapped = fullSpans.mapNotNull { span ->
            // Map original [start,end) → snippet coordinates accounting for leading ellipsis.
            val relStart = span.start - start + prefix.length
            val relEnd = span.end - start + prefix.length
            if (relEnd <= prefix.length || relStart >= prefix.length + body.length) null
            else HighlightSpan(
                start = relStart.coerceAtLeast(prefix.length),
                end = relEnd.coerceAtMost(prefix.length + body.length)
            )
        }.filter { it.end > it.start }
        return SearchSnippet(snippetText, mapped)
    }

    private val WHITESPACE = Regex("\\s+")
    const val DEFAULT_SNIPPET_CHARS = 240
}
