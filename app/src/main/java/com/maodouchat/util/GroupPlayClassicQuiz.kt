package com.maodouchat.util

// 问答知识类（测验/谜语/文字游戏等）：编解码实现（从 GroupPlayClassicPolicy 逐字搬出，零行为改动）。
internal object GroupPlayClassicQuiz {

    fun formatTwoTruthsOneLie(t1: String, t2: String, lie: String, hostLabel: String): String {
        val a = t1.trim().take(80)
        val b = t2.trim().take(80)
        val c = lie.trim().take(80)
        // Order shuffled client-side for display; lie index embedded for E2EE peers
        return "${GroupPlayPolicy.TRUTHS_PREFIX}2|${GroupPlayFieldEscape.esc(a)}|${GroupPlayFieldEscape.esc(b)}|${GroupPlayFieldEscape.esc(c)}|${hostLabel} two truths & one lie"
    }

    fun parseTwoTruthsOneLie(content: String): List<String>? {
        if (!content.startsWith(GroupPlayPolicy.TRUTHS_PREFIX)) return null
        val body = content.removePrefix(GroupPlayPolicy.TRUTHS_PREFIX)
        val parts = body.split('|').map { GroupPlayFieldEscape.unesc(it) }
        if (parts.size < 4) return null
        return listOf(parts[1], parts[2], parts[3]).filter { it.isNotBlank() }
    }

    fun formatQuiz(question: String, answer: String, options: List<String>, hostLabel: String): String {
        // 9.224 修复：先逐项 esc 再 join——此前 join 后才 esc，选项内的 ^/| 与连接符
        // 一并被转义，解析端无法区分导致选项断裂（round-trip 破坏）
        val opts = options.joinToString("^") { GroupPlayFieldEscape.esc(it.take(40)) }
        return "${GroupPlayPolicy.QUIZ_PREFIX}${GroupPlayFieldEscape.esc(question.take(120))}|${GroupPlayFieldEscape.esc(answer)}|${opts}|${hostLabel} quiz"
    }

    fun parseQuiz(content: String): Triple<String, String, List<String>>? {
        if (!content.startsWith(GroupPlayPolicy.QUIZ_PREFIX)) return null
        val body = content.removePrefix(GroupPlayPolicy.QUIZ_PREFIX)
        // 9.224：选项段先按 ^ 切分再逐项 unesc，与 format 的「先 esc 再 join」对偶；
        // q/ans 仍整段 unesc。旧格式（无真 ^ 分隔）退化为单项展示，不崩溃。
        val parts = body.split('|')
        if (parts.size < 3) return null
        val q = GroupPlayFieldEscape.unesc(parts[0])
        val ans = GroupPlayFieldEscape.unesc(parts[1])
        val opts = parts[2].split('^').filter { it.isNotBlank() }.map { GroupPlayFieldEscape.unesc(it) }
        return Triple(q, ans, opts)
    }

    fun formatCharades(prompt: String, hostLabel: String): String {
        val p = prompt.trim().ifBlank { "mystery" }.take(40)
        return "${GroupPlayPolicy.CHARADES_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} charades — act it out!"
    }

    fun parseCharades(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.CHARADES_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.CHARADES_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatNumberGuess(secret: Int, max: Int, hostLabel: String): String {
        // Secret is client-local in E2EE body; others only see range.
        return "${GroupPlayPolicy.NUMBERGUESS_PREFIX}$secret|$max|${hostLabel} number guess 1..$max"
    }

    fun parseNumberGuess(content: String): Pair<Int, Int>? {
        if (!content.startsWith(GroupPlayPolicy.NUMBERGUESS_PREFIX)) return null
        val body = content.removePrefix(GroupPlayPolicy.NUMBERGUESS_PREFIX)
        val secret = GroupPlayFieldEscape.unesc(body.substringBefore('|')).toIntOrNull() ?: return null
        val max = GroupPlayFieldEscape.unesc(body.substringAfter('|').substringBefore('|')).toIntOrNull() ?: return null
        return secret to max
    }

    fun formatRiddle(q: String, a: String, hostLabel: String): String {
        val qq = q.trim().take(80)
        val aa = a.trim().take(40)
        return "${GroupPlayPolicy.RIDDLE_PREFIX}${GroupPlayFieldEscape.esc(qq)}|${GroupPlayFieldEscape.esc(aa)}|${hostLabel} riddle"
    }

    fun parseRiddle(content: String): Pair<String, String>? {
        if (!content.startsWith(GroupPlayPolicy.RIDDLE_PREFIX)) return null
        val body = content.removePrefix(GroupPlayPolicy.RIDDLE_PREFIX)
        // 9.225：畸形格式（缺分隔符）拒绝解析，避免 a 回退为 q 的错误展示
        if (!body.contains('|')) return null
        val q = GroupPlayFieldEscape.unesc(body.substringBefore('|'))
        val a = GroupPlayFieldEscape.unesc(body.substringAfter('|').substringBefore('|'))
        if (q.isBlank()) return null
        return q to a
    }

    fun formatAlphabet(letter: String, hostLabel: String): String {
        val L = letter.trim().take(1).uppercase().ifBlank { "A" }
        return "${GroupPlayPolicy.ALPHABET_PREFIX}$L|${hostLabel} alphabet race — name something starting with $L"
    }

    fun parseAlphabet(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.ALPHABET_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.ALPHABET_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatTrivia(q: String, a: String, hostLabel: String): String {
        return "${GroupPlayPolicy.TRIVIA_PREFIX}${GroupPlayFieldEscape.esc(q.take(80))}|${GroupPlayFieldEscape.esc(a.take(40))}|${hostLabel} trivia"
    }

    fun parseTrivia(content: String): Pair<String, String>? {
        if (!content.startsWith(GroupPlayPolicy.TRIVIA_PREFIX)) return null
        val body = content.removePrefix(GroupPlayPolicy.TRIVIA_PREFIX)
        // 9.225：同 riddle——缺分隔符的畸形格式拒绝解析
        if (!body.contains('|')) return null
        val q = GroupPlayFieldEscape.unesc(body.substringBefore('|'))
        val a = GroupPlayFieldEscape.unesc(body.substringAfter('|').substringBefore('|'))
        if (q.isBlank()) return null
        return q to a
    }

    fun formatScatter(letter: String, category: String, hostLabel: String): String {
        val l = letter.trim().take(2).uppercase()
        val c = category.trim().take(24)
        return "${GroupPlayPolicy.SCATTER_PREFIX}${GroupPlayFieldEscape.esc(l)}|${GroupPlayFieldEscape.esc(c)}|${hostLabel} scattergories — name one!"
    }

    fun parseScatter(content: String): Pair<String, String>? {
        if (!content.startsWith(GroupPlayPolicy.SCATTER_PREFIX)) return null
        val body = content.removePrefix(GroupPlayPolicy.SCATTER_PREFIX)
        val letter = GroupPlayFieldEscape.unesc(body.substringBefore('|'))
        val cat = GroupPlayFieldEscape.unesc(body.substringAfter('|').substringBefore('|'))
        if (letter.isBlank() || cat.isBlank()) return null
        return letter to cat
    }

    fun formatKaraoke(line: String, hostLabel: String): String {
        val l = line.trim().take(80)
        return "${GroupPlayPolicy.KARAOKE_PREFIX}${GroupPlayFieldEscape.esc(l)}|${hostLabel} karaoke challenge"
    }

    fun parseKaraoke(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.KARAOKE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.KARAOKE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatBlindQ(q: String, hostLabel: String): String {
        val qq = q.trim().take(80)
        return "${GroupPlayPolicy.BLIND_Q_PREFIX}${GroupPlayFieldEscape.esc(qq)}|${hostLabel} blind Q — guess about someone!"
    }

    fun parseBlindQ(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.BLIND_Q_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.BLIND_Q_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatEmojiQuiz(prompt: String, answer: String, hostLabel: String): String {
        val p = prompt.trim().take(24)
        val a = answer.trim().take(40)
        return "${GroupPlayPolicy.EMOJI_QUIZ_PREFIX}${GroupPlayFieldEscape.esc(p)}|${GroupPlayFieldEscape.esc(a)}|${hostLabel} emoji quiz"
    }

    fun parseEmojiQuiz(content: String): Pair<String, String>? {
        if (!content.startsWith(GroupPlayPolicy.EMOJI_QUIZ_PREFIX)) return null
        val body = content.removePrefix(GroupPlayPolicy.EMOJI_QUIZ_PREFIX)
        if (!body.contains('|')) return null
        val p = GroupPlayFieldEscape.unesc(body.substringBefore('|'))
        val a = GroupPlayFieldEscape.unesc(body.substringAfter('|').substringBefore('|'))
        if (p.isBlank()) return null
        return p to a
    }

    fun formatDebate(topic: String, hostLabel: String): String {
        val t = topic.trim().take(80)
        return "${GroupPlayPolicy.DEBATE_PREFIX}${GroupPlayFieldEscape.esc(t)}|${hostLabel} debate — pick a side!"
    }

    fun parseDebate(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.DEBATE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.DEBATE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatMirror(line: String, hostLabel: String): String {
        val l = line.trim().take(100)
        return "${GroupPlayPolicy.MIRROR_PREFIX}${GroupPlayFieldEscape.esc(l)}|${hostLabel} mirror — repeat in your style"
    }

    fun parseMirror(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.MIRROR_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.MIRROR_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatToast(line: String, hostLabel: String): String {
        val t = line.trim().take(80)
        return "${GroupPlayPolicy.TOAST_PREFIX}${GroupPlayFieldEscape.esc(t)}|${hostLabel} friendly roast"
    }

    fun parseToast(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.TOAST_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.TOAST_PREFIX).substringBefore('|')).ifBlank { null }
    }

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

    fun formatEmojiTranslate(prompt: String, answer: String, hostLabel: String): String {
        val p = prompt.trim().take(24)
        val a = answer.trim().take(40)
        return "${GroupPlayPolicy.EMOJI_TR_PREFIX}${GroupPlayFieldEscape.esc(p)}|${GroupPlayFieldEscape.esc(a)}|${hostLabel} emoji translate"
    }

    fun parseEmojiTranslate(content: String): Pair<String, String>? {
        if (!content.startsWith(GroupPlayPolicy.EMOJI_TR_PREFIX)) return null
        val body = content.removePrefix(GroupPlayPolicy.EMOJI_TR_PREFIX)
        val p = GroupPlayFieldEscape.unesc(body.substringBefore('|'))
        val a = GroupPlayFieldEscape.unesc(body.substringAfter('|').substringBefore('|'))
        if (p.isBlank()) return null
        return p to a
    }

    fun formatTwentyQuestions(subject: String, hostLabel: String): String {
        val s = subject.trim().take(40)
        return "${GroupPlayPolicy.TWENTYQ_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} 20 questions — yes/no only!"
    }

    fun parseTwentyQuestions(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.TWENTYQ_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.TWENTYQ_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatRhyme(seed: String, hostLabel: String): String {
        val s = seed.trim().take(20)
        return "${GroupPlayPolicy.RHYME_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} rhyme chain — rhyme with '$s'"
    }

    fun parseRhyme(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.RHYME_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.RHYME_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatOddOneOut(options: String, answer: String, hostLabel: String): String {
        val o = options.trim().take(80)
        val a = answer.trim().take(40)
        return "${GroupPlayPolicy.ODDONE_PREFIX}${GroupPlayFieldEscape.esc(o)}|${GroupPlayFieldEscape.esc(a)}|${hostLabel} odd one out"
    }

    fun parseOddOneOut(content: String): Pair<String, String>? {
        if (!content.startsWith(GroupPlayPolicy.ODDONE_PREFIX)) return null
        val body = content.removePrefix(GroupPlayPolicy.ODDONE_PREFIX)
        val o = GroupPlayFieldEscape.unesc(body.substringBefore('|'))
        val a = GroupPlayFieldEscape.unesc(body.substringAfter('|').substringBefore('|'))
        if (o.isBlank()) return null
        return o to a
    }

    fun formatCategories(cat: String, hostLabel: String): String {
        val c = cat.trim().take(30)
        return "${GroupPlayPolicy.CATEGORIES_PREFIX}${GroupPlayFieldEscape.esc(c)}|${hostLabel} categories — name things in '$c'"
    }

    fun parseCategories(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.CATEGORIES_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.CATEGORIES_PREFIX).substringBefore('|')).ifBlank { null }
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

    fun formatGeoGuess(clue: String, hostLabel: String): String {
        val c = clue.trim().take(50)
        return "${GroupPlayPolicy.GEO_GUESS_PREFIX}${GroupPlayFieldEscape.esc(c)}|${hostLabel} geo guess"
    }

    fun parseGeoGuess(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.GEO_GUESS_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.GEO_GUESS_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatOneWord(word: String, hostLabel: String): String {
        val w = word.trim().take(20)
        return "${GroupPlayPolicy.ONE_WORD_PREFIX}${GroupPlayFieldEscape.esc(w)}|${hostLabel} one-word story — continue with one word"
    }

    fun parseOneWord(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.ONE_WORD_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.ONE_WORD_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatSpeedMath(q: String, hostLabel: String): String {
        val qq = q.trim().take(20)
        return "${GroupPlayPolicy.SPEED_MATH_PREFIX}${GroupPlayFieldEscape.esc(qq)}|${hostLabel} speed math — first correct wins"
    }

    fun parseSpeedMath(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SPEED_MATH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SPEED_MATH_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatAlphabetRace(start: String, hostLabel: String): String {
        val s = start.trim().take(4)
        return "${GroupPlayPolicy.ALPHABET_RACE_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} alphabet race"
    }

    fun parseAlphabetRace(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.ALPHABET_RACE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.ALPHABET_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatDebateFlash(topic: String, hostLabel: String): String {
        val tp = topic.trim().take(50)
        return "${GroupPlayPolicy.DEBATE_FLASH_PREFIX}${GroupPlayFieldEscape.esc(tp)}|${hostLabel} debate flash - 30s side"
    }

    fun parseDebateFlash(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.DEBATE_FLASH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.DEBATE_FLASH_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatQuickPoll(options: String, hostLabel: String): String {
        val o = options.trim().take(60)
        return "${GroupPlayPolicy.QUICK_POLL_PREFIX}${GroupPlayFieldEscape.esc(o)}|${hostLabel} quick poll"
    }

    fun parseQuickPoll(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.QUICK_POLL_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.QUICK_POLL_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatMirrorEcho(line: String, hostLabel: String): String {
        val l = line.trim().take(50)
        return "${GroupPlayPolicy.MIRROR_ECHO_PREFIX}${GroupPlayFieldEscape.esc(l)}|${hostLabel} mirror echo — reverse it"
    }

    fun parseMirrorEcho(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.MIRROR_ECHO_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.MIRROR_ECHO_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatFactOrFiction(item: String, hostLabel: String): String {
        val it = item.trim().take(60)
        return "${GroupPlayPolicy.FACT_OR_FICTION_PREFIX}${GroupPlayFieldEscape.esc(it)}|${hostLabel} fact or fiction"
    }

    fun parseFactOrFiction(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.FACT_OR_FICTION_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.FACT_OR_FICTION_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatWordScramble(pair: String, hostLabel: String): String {
        val p = pair.trim().take(40)
        return "${GroupPlayPolicy.WORD_SCRAMBLE_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} word scramble"
    }

    fun parseWordScramble(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.WORD_SCRAMBLE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.WORD_SCRAMBLE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatCodeBreaker(code: String, hostLabel: String): String {
        val c = code.trim().take(8)
        return "${GroupPlayPolicy.CODE_BREAKER_PREFIX}${GroupPlayFieldEscape.esc(c)}|${hostLabel} code breaker — guess digits"
    }

    fun parseCodeBreaker(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.CODE_BREAKER_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.CODE_BREAKER_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatEmojiMath(expr: String, hostLabel: String): String {
        val e = expr.trim().take(20)
        return "${GroupPlayPolicy.EMOJI_MATH_PREFIX}${GroupPlayFieldEscape.esc(e)}|${hostLabel} emoji math"
    }

    fun parseEmojiMath(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.EMOJI_MATH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.EMOJI_MATH_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatTranslateRelay(pair: String, hostLabel: String): String {
        val p = pair.trim().take(20)
        return "${GroupPlayPolicy.TRANSLATE_RELAY_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} translate relay"
    }

    fun parseTranslateRelay(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.TRANSLATE_RELAY_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.TRANSLATE_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }
}
