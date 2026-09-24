package com.maodouchat.util

/**
 * 经典群玩法模式的编解码（G328c 从 `GroupPlayPolicy` 搬出，**实现搬走、调用方零改动**）。
 *
 * 拆分依据：`GroupPlayPolicy` 是 1945 行的**扁平编解码目录**（172 个前缀、376 个函数），
 * 不是纠缠的上帝对象——所以拆法是「按模式归族搬家 + 父对象保留同名委托」，
 * 与 G88（`GroupPlaySealPolicy`）同一先例。每个 format/parse 仍是原来的实现。
 */
internal object GroupPlayClassicPolicy {

    fun rollRps(): String = com.maodouchat.group.play.GroupPkPolicy.rollRps()
    fun formatRps(choice: String, userLabel: String): String =
        com.maodouchat.group.play.GroupPkPolicy.formatRps(choice, userLabel)
    fun parseRps(content: String): String? =
        com.maodouchat.group.play.GroupPkPolicy.parseRps(content)
    fun rollNumberBomb(max: Int = 100): Pair<Int, Int> =
        com.maodouchat.group.play.GroupPkPolicy.rollNumberBomb(max)
    fun formatNumberBomb(secret: Int, max: Int, hostLabel: String): String =
        com.maodouchat.group.play.GroupPkPolicy.formatNumberBomb(secret, max, hostLabel)
    fun parseNumberBomb(content: String): Triple<Int, Int, String>? =
        com.maodouchat.group.play.GroupPkPolicy.parseNumberBomb(content)
    fun randomWordSeed(): String = com.maodouchat.group.play.GroupChainPolicy.randomWordSeed()
    fun formatWordChain(seed: String, userLabel: String): String =
        com.maodouchat.group.play.GroupChainPolicy.formatWordChain(seed, userLabel)
    fun randomRaceToken(): String = com.maodouchat.group.play.GroupPkPolicy.randomRaceToken()
    fun formatReactionRace(token: String, hostLabel: String): String =
        com.maodouchat.group.play.GroupPkPolicy.formatReactionRace(token, hostLabel)
    fun parseReactionRace(content: String): Pair<String, String>? =
        com.maodouchat.group.play.GroupPkPolicy.parseReactionRace(content)
    fun formatWouldYouRather(a: String, b: String, hostLabel: String): String {
        val left = a.trim().take(80)
        val right = b.trim().take(80)
        return "${GroupPlayPolicy.WOULD_PREFIX}${GroupPlayFieldEscape.esc(left)}|${GroupPlayFieldEscape.esc(right)}|${hostLabel} would you rather"
    }
    fun parseWouldYouRather(content: String): Triple<String, String, String>? {
        if (!content.startsWith(GroupPlayPolicy.WOULD_PREFIX)) return null
        val body = content.removePrefix(GroupPlayPolicy.WOULD_PREFIX)
        val parts = body.split('|').map { GroupPlayFieldEscape.unesc(it) }
        if (parts.size < 2) return null
        val a = parts[0]
        val b = parts[1]
        val label = parts.getOrNull(2).orEmpty()
        if (a.isBlank() || b.isBlank()) return null
        return Triple(a, b, label)
    }
    fun formatEmojiRain(hostLabel: String, emoji: String = rainEmojis.random()): String {
        val e = emoji.take(4).ifBlank { "🎉" }
        return "${GroupPlayPolicy.EMOJI_RAIN_PREFIX}${GroupPlayFieldEscape.esc(e)}|${hostLabel} started emoji rain $e"
    }
    fun parseEmojiRain(content: String): Pair<String, String>? {
        if (!content.startsWith(GroupPlayPolicy.EMOJI_RAIN_PREFIX)) return null
        val body = content.removePrefix(GroupPlayPolicy.EMOJI_RAIN_PREFIX)
        val emoji = GroupPlayFieldEscape.unesc(body.substringBefore('|')).ifBlank { return null }
        val rest = body.substringAfter('|', "")
        return emoji to rest
    }
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
    fun spinWheel(): String = spinOptions.random()
    fun formatSpin(result: String, hostLabel: String): String {
        return "${GroupPlayPolicy.SPIN_PREFIX}${GroupPlayFieldEscape.esc(result.take(40))}|${hostLabel} spun the wheel"
    }
    fun parseSpin(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SPIN_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SPIN_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatStory(seed: String, hostLabel: String): String {
        val s = seed.trim().ifBlank { "Once upon a time in a chat group..." }.take(160)
        return "${GroupPlayPolicy.STORY_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} started a story"
    }
    fun parseStory(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.STORY_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.STORY_PREFIX).substringBefore('|'))
    }
    fun formatCountdown(seconds: Int, hostLabel: String): String {
        val s = seconds.coerceIn(5, 600)
        return "${GroupPlayPolicy.COUNTDOWN_PREFIX}$s|${hostLabel} started ${s}s countdown"
    }
    fun parseCountdown(content: String): Int? {
        if (!content.startsWith(GroupPlayPolicy.COUNTDOWN_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.COUNTDOWN_PREFIX).substringBefore('|')).toIntOrNull()
    }
    fun formatBingo(board: List<String>, hostLabel: String): String {
        // 9.224：同 quiz 修复——先逐项 esc 再 join，避免格内 ^ 与连接符混淆
        val cells = board.joinToString("^") { GroupPlayFieldEscape.esc(it.take(4)) }
        return "${GroupPlayPolicy.BINGO_PREFIX}$cells|${hostLabel} bingo board"
    }
    fun parseBingo(content: String): List<String>? {
        if (!content.startsWith(GroupPlayPolicy.BINGO_PREFIX)) return null
        val body = content.removePrefix(GroupPlayPolicy.BINGO_PREFIX).substringBefore('|')
        val cells = body.split('^').filter { it.isNotBlank() }.map { GroupPlayFieldEscape.unesc(it) }
        return cells.takeIf { it.isNotEmpty() }
    }
    fun formatLottery(pool: List<String>, winner: String, hostLabel: String): String {
        // 9.224：同 quiz 修复——奖池可能含任意用户输入，必须先逐项 esc 再 join
        val p = pool.joinToString("^") { GroupPlayFieldEscape.esc(it.take(24)) }.take(200)
        return "${GroupPlayPolicy.LOTTERY_PREFIX}${GroupPlayFieldEscape.esc(winner)}|$p|${hostLabel} lottery"
    }
    fun parseLottery(content: String): Pair<String, List<String>>? {
        if (!content.startsWith(GroupPlayPolicy.LOTTERY_PREFIX)) return null
        val body = content.removePrefix(GroupPlayPolicy.LOTTERY_PREFIX)
        val winner = GroupPlayFieldEscape.unesc(body.substringBefore('|'))
        val rest = body.substringAfter('|', "")
        val pool = rest.substringBefore('|').split('^').filter { it.isNotBlank() }.map { GroupPlayFieldEscape.unesc(it) }
        if (winner.isBlank()) return null
        return winner to pool
    }
    fun formatHotSeat(target: String, hostLabel: String): String {
        val t = target.trim().ifBlank { "someone" }.take(40)
        return "${GroupPlayPolicy.HOTSEAT_PREFIX}${GroupPlayFieldEscape.esc(t)}|${hostLabel} put $t on the hot seat — ask a question!"
    }
    fun parseHotSeat(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.HOTSEAT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.HOTSEAT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatCoinFlip(side: String, hostLabel: String): String {
        val s = if (side.equals("HEADS", true)) "HEADS" else "TAILS"
        return "${GroupPlayPolicy.COINFLIP_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} flipped $s"
    }
    fun parseCoinFlip(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.COINFLIP_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.COINFLIP_PREFIX).substringBefore('|'))
    }
    fun formatRedPacketJoke(amountLabel: String, hostLabel: String): String {
        val a = amountLabel.trim().ifBlank { "lucky" }.take(24)
        return "${GroupPlayPolicy.REDPACKET_PREFIX}${GroupPlayFieldEscape.esc(a)}|${hostLabel} sent a fun red packet ($a) — claim in chat!"
    }
    fun parseRedPacketJoke(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.REDPACKET_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.REDPACKET_PREFIX).substringBefore('|'))
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
    fun formatImpostor(word: String, hostLabel: String): String {
        val w = word.trim().ifBlank { "apple" }.take(24)
        // Secret word in E2EE body; host privately knows; others discuss.
        return "${GroupPlayPolicy.IMPOSTOR_PREFIX}${GroupPlayFieldEscape.esc(w)}|${hostLabel} started impostor — find the odd one out!"
    }
    fun parseImpostor(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.IMPOSTOR_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.IMPOSTOR_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatEmojiStory(seed: String, hostLabel: String): String {
        val s = seed.trim().ifBlank { "✨" }.take(24)
        return "${GroupPlayPolicy.EMOJI_STORY_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} emoji story — continue in chat!"
    }
    fun parseEmojiStory(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.EMOJI_STORY_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.EMOJI_STORY_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatSimon(seq: String, hostLabel: String): String {
        val s = seq.take(16)
        return "${GroupPlayPolicy.SIMON_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} simon says — repeat the sequence!"
    }
    fun parseSimon(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SIMON_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SIMON_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatHotOrNot(topic: String, hostLabel: String): String {
        val t = topic.trim().ifBlank { "this idea" }.take(60)
        return "${GroupPlayPolicy.HOTORNOT_PREFIX}${GroupPlayFieldEscape.esc(t)}|${hostLabel} hot or not: $t"
    }
    fun parseHotOrNot(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.HOTORNOT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.HOTORNOT_PREFIX).substringBefore('|')).ifBlank { null }
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
    fun formatSpeedChallenge(sec: Int, hostLabel: String): String {
        val s = sec.coerceIn(5, 60)
        return "${GroupPlayPolicy.SPEED_PREFIX}$s|${hostLabel} speed challenge — reply in ${s}s!"
    }
    fun parseSpeedChallenge(content: String): Int? {
        if (!content.startsWith(GroupPlayPolicy.SPEED_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SPEED_PREFIX).substringBefore('|')).toIntOrNull()
    }
    fun randomMemoryBoard(): String = memoryEmojis.random()
    fun formatTruthOrDare(mode: String, prompt: String, hostLabel: String): String {
        val m = mode.trim().lowercase().let { if (it == "dare") "dare" else "truth" }
        val p = prompt.trim().take(80)
        return "${GroupPlayPolicy.TRUTH_OR_DARE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${GroupPlayFieldEscape.esc(p)}|${hostLabel} truth-or-dare"
    }
    fun parseTruthOrDare(content: String): Pair<String, String>? {
        if (!content.startsWith(GroupPlayPolicy.TRUTH_OR_DARE_PREFIX)) return null
        val body = content.removePrefix(GroupPlayPolicy.TRUTH_OR_DARE_PREFIX)
        if (!body.contains('|')) return null
        val mode = GroupPlayFieldEscape.unesc(body.substringBefore('|'))
        val prompt = GroupPlayFieldEscape.unesc(body.substringAfter('|').substringBefore('|'))
        if (prompt.isBlank()) return null
        return mode to prompt
    }
    fun formatNeverHaveIEver(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(100)
        return "${GroupPlayPolicy.NEVER_HAVE_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} never-have-I-ever — react if you have!"
    }
    fun parseNeverHaveIEver(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.NEVER_HAVE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.NEVER_HAVE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatMemoryMatch(board: String, hostLabel: String): String {
        val b = board.trim().ifBlank { randomMemoryBoard() }.take(24)
        return "${GroupPlayPolicy.MEMORY_MATCH_PREFIX}${GroupPlayFieldEscape.esc(b)}|${hostLabel} memory match — find pairs!"
    }
    fun parseMemoryMatch(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.MEMORY_MATCH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.MEMORY_MATCH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatDrawPrompt(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(60)
        return "${GroupPlayPolicy.DRAW_PROMPT_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} draw this (no words)!"
    }
    fun parseDrawPrompt(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.DRAW_PROMPT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.DRAW_PROMPT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun randomEmojiDuel(): String = duelEmojis.random()
    fun formatIcebreaker(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(100)
        return "${GroupPlayPolicy.ICEBREAKER_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} icebreaker"
    }
    fun parseIcebreaker(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.ICEBREAKER_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.ICEBREAKER_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatEmojiDuel(pair: String, hostLabel: String): String {
        val p = pair.trim().ifBlank { randomEmojiDuel() }.take(16)
        return "${GroupPlayPolicy.EMOJI_DUEL_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} emoji duel — pick a side!"
    }
    fun parseEmojiDuel(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.EMOJI_DUEL_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.EMOJI_DUEL_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatRapidFire(topic: String, hostLabel: String): String {
        val t = topic.trim().take(40)
        return "${GroupPlayPolicy.RAPID_FIRE_PREFIX}${GroupPlayFieldEscape.esc(t)}|${hostLabel} rapid-fire — name 5 in 20s!"
    }
    fun parseRapidFire(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.RAPID_FIRE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.RAPID_FIRE_PREFIX).substringBefore('|')).ifBlank { null }
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
    fun formatMinuteTalk(topic: String, hostLabel: String): String {
        val t = topic.trim().take(80)
        return "${GroupPlayPolicy.MINUTE_TALK_PREFIX}${GroupPlayFieldEscape.esc(t)}|${hostLabel} 60s talk — go!"
    }
    fun parseMinuteTalk(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.MINUTE_TALK_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.MINUTE_TALK_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatCaptionThis(seed: String, hostLabel: String): String {
        val s = seed.trim().take(40)
        return "${GroupPlayPolicy.CAPTION_THIS_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} caption this!"
    }
    fun parseCaptionThis(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.CAPTION_THIS_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.CAPTION_THIS_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatStorySwap(opener: String, hostLabel: String): String {
        val o = opener.trim().take(80)
        return "${GroupPlayPolicy.STORY_SWAP_PREFIX}${GroupPlayFieldEscape.esc(o)}|${hostLabel} story swap — continue!"
    }
    fun parseStorySwap(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.STORY_SWAP_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.STORY_SWAP_PREFIX).substringBefore('|')).ifBlank { null }
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
    fun randomChainSeed(): String = chainSeeds.random()
    fun formatFortune(text: String, hostLabel: String): String {
        val t = text.trim().take(80)
        return "${GroupPlayPolicy.FORTUNE_PREFIX}${GroupPlayFieldEscape.esc(t)}|${hostLabel} fortune cookie"
    }
    fun parseFortune(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.FORTUNE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.FORTUNE_PREFIX).substringBefore('|')).ifBlank { null }
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
    fun formatChainReact(seed: String, hostLabel: String): String {
        val s = seed.trim().ifBlank { randomChainSeed() }.take(8)
        return "${GroupPlayPolicy.CHAIN_REACT_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} chain react — reply with related emoji!"
    }
    fun parseChainReact(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.CHAIN_REACT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.CHAIN_REACT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun randomHideEmoji(): String = hideEmojis.random()
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
    fun formatHideSeek(emoji: String, hostLabel: String): String {
        val e = emoji.trim().ifBlank { randomHideEmoji() }.take(8)
        return "${GroupPlayPolicy.HIDESEEK_PREFIX}${GroupPlayFieldEscape.esc(e)}|${hostLabel} hide & seek — find the emoji in chat history!"
    }
    fun parseHideSeek(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.HIDESEEK_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.HIDESEEK_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatToast(line: String, hostLabel: String): String {
        val t = line.trim().take(80)
        return "${GroupPlayPolicy.TOAST_PREFIX}${GroupPlayFieldEscape.esc(t)}|${hostLabel} friendly roast"
    }
    fun parseToast(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.TOAST_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.TOAST_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatHotPotato(seconds: Int, hostLabel: String): String {
        val s = seconds.coerceIn(5, 30)
        return "${GroupPlayPolicy.HOTPOTATO_PREFIX}$s|${hostLabel} hot potato — pass in ${s}s!"
    }
    fun parseHotPotato(content: String): Int? {
        if (!content.startsWith(GroupPlayPolicy.HOTPOTATO_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.HOTPOTATO_PREFIX).substringBefore('|')).toIntOrNull()
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
    fun formatSpyfall(location: String, hostLabel: String): String {
        val loc = location.trim().take(40)
        return "${GroupPlayPolicy.SPYFALL_PREFIX}${GroupPlayFieldEscape.esc(loc)}|${hostLabel} spyfall — one spy doesn't know the place!"
    }
    fun parseSpyfall(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SPYFALL_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SPYFALL_PREFIX).substringBefore('|')).ifBlank { null }
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
    fun formatTimeCapsule(note: String, hostLabel: String): String {
        val n = note.trim().take(80)
        return "${GroupPlayPolicy.TIMECAPSULE_PREFIX}${GroupPlayFieldEscape.esc(n)}|${hostLabel} time capsule — open later"
    }
    fun parseTimeCapsule(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.TIMECAPSULE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.TIMECAPSULE_PREFIX).substringBefore('|')).ifBlank { null }
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
    fun formatLightning(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(60)
        return "${GroupPlayPolicy.LIGHTNING_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} lightning round"
    }
    fun parseLightning(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.LIGHTNING_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.LIGHTNING_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatTwoWords(seed: String, hostLabel: String): String {
        val s = seed.trim().take(20)
        return "${GroupPlayPolicy.TWO_WORDS_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} two-word story — start with '$s'"
    }
    fun parseTwoWords(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.TWO_WORDS_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.TWO_WORDS_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatWhisper(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(60)
        return "${GroupPlayPolicy.WHISPER_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} whisper challenge"
    }
    fun parseWhisper(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.WHISPER_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.WHISPER_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatCountdownRace(seconds: Int, hostLabel: String): String {
        val s = seconds.coerceIn(2, 30)
        return "${GroupPlayPolicy.COUNTDOWN_RACE_PREFIX}$s|${hostLabel} countdown race - first reply wins!"
    }
    fun parseCountdownRace(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.COUNTDOWN_RACE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.COUNTDOWN_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatEmojiMemory(board: String, hostLabel: String): String {
        val b = board.trim().take(24)
        return "${GroupPlayPolicy.EMOJI_MEMORY_PREFIX}${GroupPlayFieldEscape.esc(b)}|${hostLabel} emoji memory — memorize then recall"
    }
    fun parseEmojiMemory(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.EMOJI_MEMORY_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.EMOJI_MEMORY_PREFIX).substringBefore('|')).ifBlank { null }
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
    fun formatStorySeed(seed: String, hostLabel: String): String {
        val s = seed.trim().take(50)
        return "${GroupPlayPolicy.STORY_SEED_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} story seed — write the next sentence"
    }
    fun parseStorySeed(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.STORY_SEED_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.STORY_SEED_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatWould2(a: String, b: String, hostLabel: String): String {
        return "${GroupPlayPolicy.WOULD_YOU_PREFIX2}${GroupPlayFieldEscape.esc(a.trim().take(30))}|${GroupPlayFieldEscape.esc(b.trim().take(30))}|${hostLabel} would you rather"
    }
    fun parseWould2(content: String): Pair<String, String>? {
        if (!content.startsWith(GroupPlayPolicy.WOULD_YOU_PREFIX2)) return null
        val body = content.removePrefix(GroupPlayPolicy.WOULD_YOU_PREFIX2)
        val a = GroupPlayFieldEscape.unesc(body.substringBefore('|'))
        val b = GroupPlayFieldEscape.unesc(body.substringAfter('|').substringBefore('|'))
        if (a.isBlank() || b.isBlank()) return null
        return a to b
    }
    fun formatEmojiOnly(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(50)
        return "${GroupPlayPolicy.EMOJI_ONLY_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} emoji-only challenge"
    }
    fun parseEmojiOnly(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.EMOJI_ONLY_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.EMOJI_ONLY_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatBlindDraw(token: String, hostLabel: String): String {
        val t = token.trim().take(8)
        return "${GroupPlayPolicy.BLIND_DRAW_PREFIX}${GroupPlayFieldEscape.esc(t)}|${hostLabel} blind draw — guess the emoji"
    }
    fun parseBlindDraw(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.BLIND_DRAW_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.BLIND_DRAW_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatAlphabetRace(start: String, hostLabel: String): String {
        val s = start.trim().take(4)
        return "${GroupPlayPolicy.ALPHABET_RACE_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} alphabet race"
    }
    fun parseAlphabetRace(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.ALPHABET_RACE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.ALPHABET_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatSilentMovie(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(40)
        return "${GroupPlayPolicy.SILENT_MOVIE_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} silent movie"
    }
    fun parseSilentMovie(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SILENT_MOVIE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SILENT_MOVIE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatColorWord(pair: String, hostLabel: String): String {
        val p = pair.trim().take(30)
        return "${GroupPlayPolicy.COLOR_WORD_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} color-word"
    }
    fun parseColorWord(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.COLOR_WORD_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.COLOR_WORD_PREFIX).substringBefore('|')).ifBlank { null }
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
    fun formatSyncClap(count: String, hostLabel: String): String {
        val c = count.trim().take(4)
        return "${GroupPlayPolicy.SYNC_CLAP_PREFIX}${GroupPlayFieldEscape.esc(c)}|${hostLabel} sync clap x$c"
    }
    fun parseSyncClap(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SYNC_CLAP_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SYNC_CLAP_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatFactOrFiction(item: String, hostLabel: String): String {
        val it = item.trim().take(60)
        return "${GroupPlayPolicy.FACT_OR_FICTION_PREFIX}${GroupPlayFieldEscape.esc(it)}|${hostLabel} fact or fiction"
    }
    fun parseFactOrFiction(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.FACT_OR_FICTION_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.FACT_OR_FICTION_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatImpulseDraw(token: String, hostLabel: String): String {
        val tk = token.trim().take(8)
        return "${GroupPlayPolicy.IMPULSE_DRAW_PREFIX}${GroupPlayFieldEscape.esc(tk)}|${hostLabel} impulse draw"
    }
    fun parseImpulseDraw(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.IMPULSE_DRAW_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.IMPULSE_DRAW_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatWordScramble(pair: String, hostLabel: String): String {
        val p = pair.trim().take(40)
        return "${GroupPlayPolicy.WORD_SCRAMBLE_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} word scramble"
    }
    fun parseWordScramble(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.WORD_SCRAMBLE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.WORD_SCRAMBLE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatReactionDuel(pair: String, hostLabel: String): String {
        val p = pair.trim().take(20)
        return "${GroupPlayPolicy.REACTION_DUEL_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} reaction duel"
    }
    fun parseReactionDuel(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.REACTION_DUEL_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.REACTION_DUEL_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatCodeBreaker(code: String, hostLabel: String): String {
        val c = code.trim().take(8)
        return "${GroupPlayPolicy.CODE_BREAKER_PREFIX}${GroupPlayFieldEscape.esc(c)}|${hostLabel} code breaker — guess digits"
    }
    fun parseCodeBreaker(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.CODE_BREAKER_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.CODE_BREAKER_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatSillyLaw(law: String, hostLabel: String): String {
        val l = law.trim().take(50)
        return "${GroupPlayPolicy.SILLY_LAW_PREFIX}${GroupPlayFieldEscape.esc(l)}|${hostLabel} silly law"
    }
    fun parseSillyLaw(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SILLY_LAW_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SILLY_LAW_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatEmojiMath(expr: String, hostLabel: String): String {
        val e = expr.trim().take(20)
        return "${GroupPlayPolicy.EMOJI_MATH_PREFIX}${GroupPlayFieldEscape.esc(e)}|${hostLabel} emoji math"
    }
    fun parseEmojiMath(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.EMOJI_MATH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.EMOJI_MATH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatPinTheMood(mood: String, hostLabel: String): String {
        val m = mood.trim().take(20)
        return "${GroupPlayPolicy.PIN_THE_MOOD_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} pin the mood"
    }
    fun parsePinTheMood(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.PIN_THE_MOOD_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.PIN_THE_MOOD_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatRevokeRush(window: String, hostLabel: String): String {
        val w = window.trim().take(10)
        return "${GroupPlayPolicy.REVOKE_RUSH_PREFIX}${GroupPlayFieldEscape.esc(w)}|${hostLabel} revoke rush"
    }
    fun parseRevokeRush(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.REVOKE_RUSH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.REVOKE_RUSH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatSecretSignal(signal: String, hostLabel: String): String {
        val s = signal.trim().take(30)
        return "${GroupPlayPolicy.SECRET_SIGNAL_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} secret signal"
    }
    fun parseSecretSignal(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SECRET_SIGNAL_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SECRET_SIGNAL_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatIdeaRelay(seed: String, hostLabel: String): String {
        val s = seed.trim().take(40)
        return "${GroupPlayPolicy.IDEA_RELAY_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} idea relay"
    }
    fun parseIdeaRelay(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.IDEA_RELAY_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.IDEA_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatTempoTap(beat: String, hostLabel: String): String {
        val b = beat.trim().take(12)
        return "${GroupPlayPolicy.TEMPO_TAP_PREFIX}${GroupPlayFieldEscape.esc(b)}|${hostLabel} tempo tap"
    }
    fun parseTempoTap(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.TEMPO_TAP_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.TEMPO_TAP_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatTranslateRelay(pair: String, hostLabel: String): String {
        val p = pair.trim().take(20)
        return "${GroupPlayPolicy.TRANSLATE_RELAY_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} translate relay"
    }
    fun parseTranslateRelay(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.TRANSLATE_RELAY_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.TRANSLATE_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatInviteRace(mode: String, hostLabel: String): String {
        val m = mode.trim().take(30)
        return "${GroupPlayPolicy.INVITE_RACE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} invite race"
    }
    fun parseInviteRace(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.INVITE_RACE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.INVITE_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatMentionMayhem(mode: String, hostLabel: String): String {
        val m = mode.trim().take(30)
        return "${GroupPlayPolicy.MENTION_MAYHEM_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} mention mayhem"
    }
    fun parseMentionMayhem(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.MENTION_MAYHEM_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.MENTION_MAYHEM_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatLinkHunt(mode: String, hostLabel: String): String {
        val m = mode.trim().take(30)
        return "${GroupPlayPolicy.LINK_HUNT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} link hunt"
    }
    fun parseLinkHunt(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.LINK_HUNT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.LINK_HUNT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatNudgeDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.NUDGE_DASH_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} nudge dash"
    }
    fun parseNudgeDash(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.NUDGE_DASH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.NUDGE_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatCodeCheck(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.CODE_CHECK_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} code check"
    }
    fun parseCodeCheck(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.CODE_CHECK_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.CODE_CHECK_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatTrustSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.TRUST_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} trust sprint"
    }
    fun parseTrustSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.TRUST_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.TRUST_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatMoodMeter(scale: String, hostLabel: String): String {
        val s = scale.trim().take(30)
        return "${GroupPlayPolicy.MOOD_METER_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} mood meter"
    }
    fun parseMoodMeter(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.MOOD_METER_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.MOOD_METER_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatFocusSprint(window: String, hostLabel: String): String {
        val w = window.trim().take(10)
        return "${GroupPlayPolicy.FOCUS_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(w)}|${hostLabel} focus sprint"
    }
    fun parseFocusSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.FOCUS_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.FOCUS_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatGratitudeRound(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(40)
        return "${GroupPlayPolicy.GRATITUDE_ROUND_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} gratitude round"
    }
    fun parseGratitudeRound(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.GRATITUDE_ROUND_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.GRATITUDE_ROUND_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatQrQuest(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.QR_QUEST_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} qr quest"
    }
    fun parseQrQuest(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.QR_QUEST_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.QR_QUEST_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatContactSwap(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.CONTACT_SWAP_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} contact swap"
    }
    fun parseContactSwap(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.CONTACT_SWAP_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.CONTACT_SWAP_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatScanSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.SCAN_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} scan sprint"
    }
    fun parseScanSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SCAN_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SCAN_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatSpoilerRace(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.SPOILER_RACE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} spoiler race"
    }
    fun parseSpoilerRace(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SPOILER_RACE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SPOILER_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatBlurBattle(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.BLUR_BATTLE_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} blur battle"
    }
    fun parseBlurBattle(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.BLUR_BATTLE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.BLUR_BATTLE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatDownloadDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.DOWNLOAD_DASH_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} download dash"
    }
    fun parseDownloadDash(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.DOWNLOAD_DASH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.DOWNLOAD_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatPinDrop(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.PIN_DROP_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} pin drop"
    }
    fun parsePinDrop(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.PIN_DROP_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.PIN_DROP_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatFileRelay(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.FILE_RELAY_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} file relay"
    }
    fun parseFileRelay(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.FILE_RELAY_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.FILE_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatMapDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.MAP_DASH_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} map dash"
    }
    fun parseMapDash(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.MAP_DASH_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.MAP_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatVaultLock(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.VAULT_LOCK_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} vault lock"
    }
    fun parseVaultLock(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.VAULT_LOCK_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.VAULT_LOCK_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatWatermarkHunt(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.WATERMARK_HUNT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} watermark hunt"
    }
    fun parseWatermarkHunt(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.WATERMARK_HUNT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.WATERMARK_HUNT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatSecureSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GroupPlayPolicy.SECURE_SPRINT_PREFIX}${GroupPlayFieldEscape.esc(m)}|${hostLabel} secure sprint"
    }
    fun parseSecureSprint(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SECURE_SPRINT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SECURE_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
}
