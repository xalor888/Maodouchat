package com.maodouchat.contacts

/**
 * Pure helpers for the contacts alphabet index (W3-04).
 * UI keeps sticky headers + one leading "new group" row at index 0.
 */
object ContactsIndexPolicy {
    /** First section letter for a display name: A–Z, CJK character, or "#". */
    fun initialFor(name: String): String {
        if (name.isBlank()) return "#"
        val first = name.trim().first()
        return when {
            first in 'A'..'Z' || first in 'a'..'z' -> first.uppercaseChar().toString()
            first in '\u4e00'..'\u9fff' -> first.toString()
            else -> "#"
        }
    }

    /**
     * Absolute LazyColumn index for [targetLetter] when the list is:
     * item 0 = new-group row, then for each letter: header + N contacts.
     */
    fun letterListIndex(
        orderedLetters: List<String>,
        sizes: Map<String, Int>,
        targetLetter: String,
        leadingFixedItems: Int = 1
    ): Int {
        if (targetLetter !in orderedLetters) return -1
        var index = leadingFixedItems
        for (letter in orderedLetters) {
            if (letter == targetLetter) return index
            index += 1 + (sizes[letter] ?: 0)
        }
        return -1
    }

    fun groupByInitial(names: List<Pair<String, String>>): Map<String, List<String>> {
        return names
            .groupBy { initialFor(it.second) }
            .mapValues { (_, rows) -> rows.map { it.first } }
            .toSortedMap()
    }
}
