package com.maodouchat.util

// 文字游戏类（文字玩法/接龙/字谜等）：编解码实现（从 GroupPlayClassicQuiz 逐字搬出，零行为改动）。
internal object GroupPlayClassicWordGames {

    fun formatWordHint(hint: String, answer: String, hostLabel: String): String {
        val h = hint.trim().take(60)
        val a = answer.trim().take(40)
        return "${GroupPlayPolicy.WORDHINT_PREFIX}${GroupPlayFieldEscape.esc(h)}|${GroupPlayFieldEscape.esc(a)}|${hostLabel} word hint"
    }

    fun parseWordHint(content: String): Pair<String, String>? {
        if (!content.startsWith(GroupPlayPolicy.WORDHINT_PREFIX)) return null
        val body = content.removePrefix(GroupPlayPolicy.WORDHINT_PREFIX)
        val h = GroupPlayFieldEscape.unesc(body.substringBefore('|'))
        val a = GroupPlayFieldEscape.unesc(body.substringAfter('|').substringBefore('|'))
        if (h.isBlank()) return null
        return h to a
    }

    fun formatAcrostic(seed: String, hostLabel: String): String {
        val s = seed.trim().take(20)
        return "${GroupPlayPolicy.ACROSTIC_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} acrostic — start each line with letters of '$s'"
    }

    fun parseAcrostic(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.ACROSTIC_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.ACROSTIC_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatRhyme(seed: String, hostLabel: String): String {
        val s = seed.trim().take(20)
        return "${GroupPlayPolicy.RHYME_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} rhyme chain — rhyme with '$s'"
    }

    fun parseRhyme(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.RHYME_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.RHYME_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatPasswordGame(hint: String, hostLabel: String): String {
        val h = hint.trim().take(40)
        return "${GroupPlayPolicy.PASSWORD_PREFIX}${GroupPlayFieldEscape.esc(h)}|${hostLabel} password game — guess under rules"
    }

    fun parsePasswordGame(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.PASSWORD_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.PASSWORD_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatTaboo(card: String, hostLabel: String): String {
        val c = card.trim().take(60)
        val word = c.substringBefore('|')
        return "${GroupPlayPolicy.TABOO_PREFIX}${GroupPlayFieldEscape.esc(c)}|${hostLabel} taboo — describe '$word' without banned words"
    }

    fun parseTaboo(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.TABOO_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.TABOO_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatTwoWords(seed: String, hostLabel: String): String {
        val s = seed.trim().take(20)
        return "${GroupPlayPolicy.TWO_WORDS_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} two-word story — start with '$s'"
    }

    fun parseTwoWords(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.TWO_WORDS_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.TWO_WORDS_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatOneWord(word: String, hostLabel: String): String {
        val w = word.trim().take(20)
        return "${GroupPlayPolicy.ONE_WORD_PREFIX}${GroupPlayFieldEscape.esc(w)}|${hostLabel} one-word story — continue with one word"
    }

    fun parseOneWord(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.ONE_WORD_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.ONE_WORD_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatWordScramble(pair: String, hostLabel: String): String {
        val p = pair.trim().take(40)
        return "${GroupPlayPolicy.WORD_SCRAMBLE_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} word scramble"
    }

    fun parseWordScramble(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.WORD_SCRAMBLE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.WORD_SCRAMBLE_PREFIX).substringBefore('|')).ifBlank { null }
    }
}
