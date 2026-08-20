package com.maodouchat.util

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Group play helpers (polls, dice, check-in) — pure/local formatting.
 * Poll persistence is server-backed; dice is ephemeral system text with optional E2EE payload.
 */
object GroupPlayPolicy {
    private fun esc(s: String): String = s.replace("|", "\u0001").replace("^", "\u0002")
    private fun unesc(s: String): String = s.replace("\u0001", "|").replace("\u0002", "^")
    const val DICE_PREFIX = "DICE:"
    const val POLL_PREFIX = "POLL:"
    const val CHECKIN_PREFIX = "CHECKIN:"

    fun rollDice(sides: Int = 6): Int {
        val s = sides.coerceIn(2, 100)
        return Random.nextInt(1, s + 1)
    }

    fun formatDiceMessage(value: Int, sides: Int = 6, rollerName: String = ""): String {
        val who = rollerName.takeIf { it.isNotBlank() }?.let { "$it " }.orEmpty()
        return "${DICE_PREFIX}$sides:$value|${who}rolled $value / $sides"
    }

    fun parseDice(content: String): Pair<Int, Int>? {
        if (!content.startsWith(DICE_PREFIX)) return null
        val body = content.removePrefix(DICE_PREFIX)
        val head = unesc(body.substringBefore('|'))
        val parts = head.split(':')
        if (parts.size < 2) return null
        val sides = parts[0].toIntOrNull() ?: return null
        val value = parts[1].toIntOrNull() ?: return null
        return sides to value
    }

    fun formatCheckIn(dayStreak: Int, userLabel: String): String {
        return "${CHECKIN_PREFIX}$dayStreak|${userLabel} checked in · streak $dayStreak"
    }

    fun buildPollPayload(
        pollId: String,
        question: String,
        options: List<String>,
        multi: Boolean,
        anonymous: Boolean
    ): String {
        val o = JSONObject()
        o.put("id", pollId)
        o.put("q", question.take(200))
        o.put("options", JSONArray(options.map { it.take(80) }))
        o.put("multi", multi)
        o.put("anonymous", anonymous)
        return POLL_PREFIX + o.toString()
    }

    fun parsePoll(content: String): JSONObject? {
        if (!content.startsWith(POLL_PREFIX)) return null
        return runCatching { JSONObject(content.removePrefix(POLL_PREFIX)) }.getOrNull()
    }

    fun formatLuckyDraw(pickerName: String, targetName: String): String {
        return "LUCKY:${esc(pickerName)}|$targetName"
    }

    fun parseLuckyDraw(content: String): Pair<String, String>? {
        if (!content.startsWith("LUCKY:")) return null
        val body = content.removePrefix("LUCKY:")
        val a = unesc(body.substringBefore('|'))
        val b = body.substringAfter('|', "")
        if (a.isBlank() || b.isBlank()) return null
        return a to b
    }

    const val RPS_PREFIX = "RPS:"
    const val TRUTH_PREFIX = "TRUTH:"
    const val ANON_PREFIX = "ANON:"

    fun rollRps(): String = rpsChoices.random()

    fun formatRps(choice: String, userLabel: String): String {
        val c = choice.lowercase().let { if (it in rpsChoices) it else rollRps() }
        val emoji = when (c) {
            "rock" -> "[R]"
            "paper" -> "[P]"
            else -> "[S]"
        }
        return "${RPS_PREFIX}${esc(c)}|${userLabel} played $emoji $c"
    }

    fun parseRps(content: String): String? {
        if (!content.startsWith(RPS_PREFIX)) return null
        return unesc(content.removePrefix(RPS_PREFIX).substringBefore('|').trim()).ifBlank { null }
    }

    fun randomTruthPrompt(): String = truthPrompts.random()

    fun formatTruthPrompt(userLabel: String, prompt: String = randomTruthPrompt()): String {
        return "${TRUTH_PREFIX}${userLabel}|$prompt"
    }

    fun formatAnonBox(userLabel: String, text: String): String {
        val body = text.trim().take(280)
        return "${ANON_PREFIX}***|$body"
    }


    const val BOMB_PREFIX = "BOMB:"
    const val WORD_PREFIX = "WORD:"

    fun rollNumberBomb(max: Int = 100): Pair<Int, Int> {
        val hi = max.coerceIn(10, 1000)
        val secret = Random.nextInt(1, hi + 1)
        return secret to hi
    }

    fun formatNumberBomb(secret: Int, max: Int, hostLabel: String): String {
        // Secret is embedded for E2EE-only peers; UI should not reveal until guess flow local.
        return "${BOMB_PREFIX}$max:$secret|${hostLabel} started number bomb (1-$max)"
    }

    fun parseNumberBomb(content: String): Triple<Int, Int, String>? {
        if (!content.startsWith(BOMB_PREFIX)) return null
        val body = content.removePrefix(BOMB_PREFIX)
        val head = unesc(body.substringBefore('|'))
        val parts = head.split(':')
        if (parts.size < 2) return null
        val max = parts[0].toIntOrNull() ?: return null
        val secret = parts[1].toIntOrNull() ?: return null
        val label = body.substringAfter('|', "")
        return Triple(max, secret, label)
    }

    fun randomWordSeed(): String = wordChainSeeds.random()

    fun formatWordChain(seed: String, userLabel: String): String {
        return "${WORD_PREFIX}${seed}|${userLabel} word chain: start with '$seed'"
    }


    const val RACE_PREFIX = "RACE:"

    fun randomRaceToken(): String = raceTokens.random()

    fun formatReactionRace(token: String, hostLabel: String): String {
        val t = token.ifBlank { randomRaceToken() }.take(16)
        return "${RACE_PREFIX}${esc(t)}|${hostLabel} reaction race: first to reply with $t wins"
    }

    fun parseReactionRace(content: String): Pair<String, String>? {
        if (!content.startsWith(RACE_PREFIX)) return null
        val body = content.removePrefix(RACE_PREFIX)
        val token = unesc(body.substringBefore('|')).ifBlank { return null }
        val rest = body.substringAfter('|', "")
        return token to rest
    }



    const val WOULD_PREFIX = "WOULD:"
    const val EMOJI_RAIN_PREFIX = "EMOJI_RAIN:"

    fun randomWouldPair(): Pair<String, String> {
        val p = wouldPrompts.random()
        val a = p.substringBefore('|')
        val b = p.substringAfter('|')
        return a to b
    }

    fun formatWouldYouRather(a: String, b: String, hostLabel: String): String {
        val left = a.trim().take(80)
        val right = b.trim().take(80)
        return "${WOULD_PREFIX}${esc(left)}|${esc(right)}|${hostLabel} would you rather"
    }

    fun parseWouldYouRather(content: String): Triple<String, String, String>? {
        if (!content.startsWith(WOULD_PREFIX)) return null
        val body = content.removePrefix(WOULD_PREFIX)
        val parts = body.split('|').map { unesc(it) }
        if (parts.size < 2) return null
        val a = parts[0]
        val b = parts[1]
        val label = parts.getOrNull(2).orEmpty()
        if (a.isBlank() || b.isBlank()) return null
        return Triple(a, b, label)
    }

    fun formatEmojiRain(hostLabel: String, emoji: String = rainEmojis.random()): String {
        val e = emoji.take(4).ifBlank { "🎉" }
        return "${EMOJI_RAIN_PREFIX}${esc(e)}|${hostLabel} started emoji rain $e"
    }

    fun parseEmojiRain(content: String): Pair<String, String>? {
        if (!content.startsWith(EMOJI_RAIN_PREFIX)) return null
        val body = content.removePrefix(EMOJI_RAIN_PREFIX)
        val emoji = unesc(body.substringBefore('|')).ifBlank { return null }
        val rest = body.substringAfter('|', "")
        return emoji to rest
    }



    const val TRUTHS_PREFIX = "TRUTHS:"
    const val QUIZ_PREFIX = "QUIZ:"

    fun formatTwoTruthsOneLie(t1: String, t2: String, lie: String, hostLabel: String): String {
        val a = t1.trim().take(80)
        val b = t2.trim().take(80)
        val c = lie.trim().take(80)
        // Order shuffled client-side for display; lie index embedded for E2EE peers
        return "${TRUTHS_PREFIX}2|${esc(a)}|${esc(b)}|${esc(c)}|${hostLabel} two truths & one lie"
    }

    fun parseTwoTruthsOneLie(content: String): List<String>? {
        if (!content.startsWith(TRUTHS_PREFIX)) return null
        val body = content.removePrefix(TRUTHS_PREFIX)
        val parts = body.split('|').map { unesc(it) }
        if (parts.size < 4) return null
        return listOf(parts[1], parts[2], parts[3]).filter { it.isNotBlank() }
    }

    fun randomQuiz(): Triple<String, String, List<String>> {
        val raw = quizBank.random()
        val q = raw.substringBefore('|')
        val parts = raw.split('|')
        val answer = parts.getOrElse(1) { "" }
        val options = parts.drop(1).shuffled()
        return Triple(q, answer, options)
    }

    fun formatQuiz(question: String, answer: String, options: List<String>, hostLabel: String): String {
        // 9.224 修复：先逐项 esc 再 join——此前 join 后才 esc，选项内的 ^/| 与连接符
        // 一并被转义，解析端无法区分导致选项断裂（round-trip 破坏）
        val opts = options.joinToString("^") { esc(it.take(40)) }
        return "${QUIZ_PREFIX}${esc(question.take(120))}|${esc(answer)}|${opts}|${hostLabel} quiz"
    }

    fun parseQuiz(content: String): Triple<String, String, List<String>>? {
        if (!content.startsWith(QUIZ_PREFIX)) return null
        val body = content.removePrefix(QUIZ_PREFIX)
        // 9.224：选项段先按 ^ 切分再逐项 unesc，与 format 的「先 esc 再 join」对偶；
        // q/ans 仍整段 unesc。旧格式（无真 ^ 分隔）退化为单项展示，不崩溃。
        val parts = body.split('|')
        if (parts.size < 3) return null
        val q = unesc(parts[0])
        val ans = unesc(parts[1])
        val opts = parts[2].split('^').filter { it.isNotBlank() }.map { unesc(it) }
        return Triple(q, ans, opts)
    }


    const val SPIN_PREFIX = "SPIN:"
    const val STORY_PREFIX = "STORY:"
    const val COUNTDOWN_PREFIX = "COUNTDOWN:"

    fun spinWheel(): String = spinOptions.random()

    fun formatSpin(result: String, hostLabel: String): String {
        return "${SPIN_PREFIX}${esc(result.take(40))}|${hostLabel} spun the wheel"
    }

    fun parseSpin(content: String): String? {
        if (!content.startsWith(SPIN_PREFIX)) return null
        return unesc(content.removePrefix(SPIN_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatStory(seed: String, hostLabel: String): String {
        val s = seed.trim().ifBlank { "Once upon a time in a chat group..." }.take(160)
        return "${STORY_PREFIX}${esc(s)}|${hostLabel} started a story"
    }

    fun parseStory(content: String): String? {
        if (!content.startsWith(STORY_PREFIX)) return null
        return unesc(content.removePrefix(STORY_PREFIX).substringBefore('|'))
    }

    fun formatCountdown(seconds: Int, hostLabel: String): String {
        val s = seconds.coerceIn(5, 600)
        return "${COUNTDOWN_PREFIX}$s|${hostLabel} started ${s}s countdown"
    }

    fun parseCountdown(content: String): Int? {
        if (!content.startsWith(COUNTDOWN_PREFIX)) return null
        return unesc(content.removePrefix(COUNTDOWN_PREFIX).substringBefore('|')).toIntOrNull()
    }


    const val BINGO_PREFIX = "BINGO:"
    const val LOTTERY_PREFIX = "LOTTERY:"
    const val HOTSEAT_PREFIX = "HOTSEAT:"

    fun randomBingoBoard(): List<String> = bingoEmojis.shuffled().take(6)

    fun formatBingo(board: List<String>, hostLabel: String): String {
        // 9.224：同 quiz 修复——先逐项 esc 再 join，避免格内 ^ 与连接符混淆
        val cells = board.joinToString("^") { esc(it.take(4)) }
        return "${BINGO_PREFIX}$cells|${hostLabel} bingo board"
    }

    fun parseBingo(content: String): List<String>? {
        if (!content.startsWith(BINGO_PREFIX)) return null
        val body = content.removePrefix(BINGO_PREFIX).substringBefore('|')
        val cells = body.split('^').filter { it.isNotBlank() }.map { unesc(it) }
        return cells.takeIf { it.isNotEmpty() }
    }

    fun formatLottery(pool: List<String>, winner: String, hostLabel: String): String {
        // 9.224：同 quiz 修复——奖池可能含任意用户输入，必须先逐项 esc 再 join
        val p = pool.joinToString("^") { esc(it.take(24)) }.take(200)
        return "${LOTTERY_PREFIX}${esc(winner)}|$p|${hostLabel} lottery"
    }

    fun parseLottery(content: String): Pair<String, List<String>>? {
        if (!content.startsWith(LOTTERY_PREFIX)) return null
        val body = content.removePrefix(LOTTERY_PREFIX)
        val winner = unesc(body.substringBefore('|'))
        val rest = body.substringAfter('|', "")
        val pool = rest.substringBefore('|').split('^').filter { it.isNotBlank() }.map { unesc(it) }
        if (winner.isBlank()) return null
        return winner to pool
    }

    fun formatHotSeat(target: String, hostLabel: String): String {
        val t = target.trim().ifBlank { "someone" }.take(40)
        return "${HOTSEAT_PREFIX}${esc(t)}|${hostLabel} put $t on the hot seat — ask a question!"
    }

    fun parseHotSeat(content: String): String? {
        if (!content.startsWith(HOTSEAT_PREFIX)) return null
        return unesc(content.removePrefix(HOTSEAT_PREFIX).substringBefore('|')).ifBlank { null }
    }


    const val COINFLIP_PREFIX = "COINFLIP:"
    const val REDPACKET_PREFIX = "REDPACKET:"

    fun flipCoin(): String = if (Random.nextBoolean()) "HEADS" else "TAILS"

    fun formatCoinFlip(side: String, hostLabel: String): String {
        val s = if (side.equals("HEADS", true)) "HEADS" else "TAILS"
        return "${COINFLIP_PREFIX}${esc(s)}|${hostLabel} flipped $s"
    }

    fun parseCoinFlip(content: String): String? {
        if (!content.startsWith(COINFLIP_PREFIX)) return null
        return unesc(content.removePrefix(COINFLIP_PREFIX).substringBefore('|'))
    }

    fun formatRedPacketJoke(amountLabel: String, hostLabel: String): String {
        val a = amountLabel.trim().ifBlank { "lucky" }.take(24)
        return "${REDPACKET_PREFIX}${esc(a)}|${hostLabel} sent a fun red packet ($a) — claim in chat!"
    }

    fun parseRedPacketJoke(content: String): String? {
        if (!content.startsWith(REDPACKET_PREFIX)) return null
        return unesc(content.removePrefix(REDPACKET_PREFIX).substringBefore('|'))
    }

    const val CHARADES_PREFIX = "CHARADES:"
    const val NUMBERGUESS_PREFIX = "NUMGUESS:"

    fun randomCharadesPrompt(): String = charadesPrompts.random()

    fun formatCharades(prompt: String, hostLabel: String): String {
        val p = prompt.trim().ifBlank { "mystery" }.take(40)
        return "${CHARADES_PREFIX}${esc(p)}|${hostLabel} charades — act it out!"
    }

    fun parseCharades(content: String): String? {
        if (!content.startsWith(CHARADES_PREFIX)) return null
        return unesc(content.removePrefix(CHARADES_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun rollNumberGuess(max: Int = 100): Pair<Int, Int> {
        val m = max.coerceIn(10, 1000)
        return (1..m).random() to m
    }

    fun formatNumberGuess(secret: Int, max: Int, hostLabel: String): String {
        // Secret is client-local in E2EE body; others only see range.
        return "${NUMBERGUESS_PREFIX}$secret|$max|${hostLabel} number guess 1..$max"
    }

    fun parseNumberGuess(content: String): Pair<Int, Int>? {
        if (!content.startsWith(NUMBERGUESS_PREFIX)) return null
        val body = content.removePrefix(NUMBERGUESS_PREFIX)
        val secret = unesc(body.substringBefore('|')).toIntOrNull() ?: return null
        val max = unesc(body.substringAfter('|').substringBefore('|')).toIntOrNull() ?: return null
        return secret to max
    }

    const val IMPOSTOR_PREFIX = "IMPOSTOR:"
    const val RIDDLE_PREFIX = "RIDDLE:"
    const val EMOJI_STORY_PREFIX = "EMOJISTORY:"

    fun randomRiddle(): Pair<String, String> = riddles.random()

    fun formatRiddle(q: String, a: String, hostLabel: String): String {
        val qq = q.trim().take(80)
        val aa = a.trim().take(40)
        return "${RIDDLE_PREFIX}${esc(qq)}|${esc(aa)}|${hostLabel} riddle"
    }

    fun parseRiddle(content: String): Pair<String, String>? {
        if (!content.startsWith(RIDDLE_PREFIX)) return null
        val body = content.removePrefix(RIDDLE_PREFIX)
        // 9.225：畸形格式（缺分隔符）拒绝解析，避免 a 回退为 q 的错误展示
        if (!body.contains('|')) return null
        val q = unesc(body.substringBefore('|'))
        val a = unesc(body.substringAfter('|').substringBefore('|'))
        if (q.isBlank()) return null
        return q to a
    }

    fun formatImpostor(word: String, hostLabel: String): String {
        val w = word.trim().ifBlank { "apple" }.take(24)
        // Secret word in E2EE body; host privately knows; others discuss.
        return "${IMPOSTOR_PREFIX}${esc(w)}|${hostLabel} started impostor — find the odd one out!"
    }

    fun parseImpostor(content: String): String? {
        if (!content.startsWith(IMPOSTOR_PREFIX)) return null
        return unesc(content.removePrefix(IMPOSTOR_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun randomEmojiStorySeed(): String = emojiStorySeeds.random()

    fun formatEmojiStory(seed: String, hostLabel: String): String {
        val s = seed.trim().ifBlank { "✨" }.take(24)
        return "${EMOJI_STORY_PREFIX}${esc(s)}|${hostLabel} emoji story — continue in chat!"
    }

    fun parseEmojiStory(content: String): String? {
        if (!content.startsWith(EMOJI_STORY_PREFIX)) return null
        return unesc(content.removePrefix(EMOJI_STORY_PREFIX).substringBefore('|')).ifBlank { null }
    }

    const val SIMON_PREFIX = "SIMON:"
    const val HOTORNOT_PREFIX = "HOTORNOT:"
    const val ALPHABET_PREFIX = "ALPHABET:"

    fun randomSimonSequence(len: Int = 4): String =
        (1..len.coerceIn(3, 8)).joinToString("") { simonTokens.random() }

    fun formatSimon(seq: String, hostLabel: String): String {
        val s = seq.take(16)
        return "${SIMON_PREFIX}${esc(s)}|${hostLabel} simon says — repeat the sequence!"
    }

    fun parseSimon(content: String): String? {
        if (!content.startsWith(SIMON_PREFIX)) return null
        return unesc(content.removePrefix(SIMON_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatHotOrNot(topic: String, hostLabel: String): String {
        val t = topic.trim().ifBlank { "this idea" }.take(60)
        return "${HOTORNOT_PREFIX}${esc(t)}|${hostLabel} hot or not: $t"
    }

    fun parseHotOrNot(content: String): String? {
        if (!content.startsWith(HOTORNOT_PREFIX)) return null
        return unesc(content.removePrefix(HOTORNOT_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatAlphabet(letter: String, hostLabel: String): String {
        val L = letter.trim().take(1).uppercase().ifBlank { "A" }
        return "${ALPHABET_PREFIX}$L|${hostLabel} alphabet race — name something starting with $L"
    }

    fun parseAlphabet(content: String): String? {
        if (!content.startsWith(ALPHABET_PREFIX)) return null
        return unesc(content.removePrefix(ALPHABET_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun randomAlphabetLetter(): String = ('A'..'Z').random().toString()

    const val TRIVIA_PREFIX = "TRIVIA:"
    const val SPEED_PREFIX = "SPEED:"

    fun randomTrivia(): Pair<String, String> = triviaQA.random()

    fun formatTrivia(q: String, a: String, hostLabel: String): String {
        return "${TRIVIA_PREFIX}${esc(q.take(80))}|${esc(a.take(40))}|${hostLabel} trivia"
    }

    fun parseTrivia(content: String): Pair<String, String>? {
        if (!content.startsWith(TRIVIA_PREFIX)) return null
        val body = content.removePrefix(TRIVIA_PREFIX)
        // 9.225：同 riddle——缺分隔符的畸形格式拒绝解析
        if (!body.contains('|')) return null
        val q = unesc(body.substringBefore('|'))
        val a = unesc(body.substringAfter('|').substringBefore('|'))
        if (q.isBlank()) return null
        return q to a
    }

    fun formatSpeedChallenge(sec: Int, hostLabel: String): String {
        val s = sec.coerceIn(5, 60)
        return "${SPEED_PREFIX}$s|${hostLabel} speed challenge — reply in ${s}s!"
    }

    fun parseSpeedChallenge(content: String): Int? {
        if (!content.startsWith(SPEED_PREFIX)) return null
        return unesc(content.removePrefix(SPEED_PREFIX).substringBefore('|')).toIntOrNull()
    }

    const val TRUTH_OR_DARE_PREFIX = "TRUTHDARE:"
    const val NEVER_HAVE_PREFIX = "NEVERHAVE:"
    const val MEMORY_MATCH_PREFIX = "MEMORY:"
    const val DRAW_PROMPT_PREFIX = "DRAWPROMPT:"

    fun randomDare(): String = dares.random()
    fun randomNeverHave(): String = neverHave.random()
    fun randomDrawPrompt(): String = drawPrompts.random()
    fun randomMemoryBoard(): String = memoryEmojis.random()

    fun formatTruthOrDare(mode: String, prompt: String, hostLabel: String): String {
        val m = mode.trim().lowercase().let { if (it == "dare") "dare" else "truth" }
        val p = prompt.trim().take(80)
        return "${TRUTH_OR_DARE_PREFIX}${esc(m)}|${esc(p)}|${hostLabel} truth-or-dare"
    }

    fun parseTruthOrDare(content: String): Pair<String, String>? {
        if (!content.startsWith(TRUTH_OR_DARE_PREFIX)) return null
        val body = content.removePrefix(TRUTH_OR_DARE_PREFIX)
        if (!body.contains('|')) return null
        val mode = unesc(body.substringBefore('|'))
        val prompt = unesc(body.substringAfter('|').substringBefore('|'))
        if (prompt.isBlank()) return null
        return mode to prompt
    }

    fun formatNeverHaveIEver(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(100)
        return "${NEVER_HAVE_PREFIX}${esc(p)}|${hostLabel} never-have-I-ever — react if you have!"
    }

    fun parseNeverHaveIEver(content: String): String? {
        if (!content.startsWith(NEVER_HAVE_PREFIX)) return null
        return unesc(content.removePrefix(NEVER_HAVE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatMemoryMatch(board: String, hostLabel: String): String {
        val b = board.trim().ifBlank { randomMemoryBoard() }.take(24)
        return "${MEMORY_MATCH_PREFIX}${esc(b)}|${hostLabel} memory match — find pairs!"
    }

    fun parseMemoryMatch(content: String): String? {
        if (!content.startsWith(MEMORY_MATCH_PREFIX)) return null
        return unesc(content.removePrefix(MEMORY_MATCH_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatDrawPrompt(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(60)
        return "${DRAW_PROMPT_PREFIX}${esc(p)}|${hostLabel} draw this (no words)!"
    }

    fun parseDrawPrompt(content: String): String? {
        if (!content.startsWith(DRAW_PROMPT_PREFIX)) return null
        return unesc(content.removePrefix(DRAW_PROMPT_PREFIX).substringBefore('|')).ifBlank { null }
    }

    const val ICEBREAKER_PREFIX = "ICEBREAKER:"
    const val EMOJI_DUEL_PREFIX = "EMOJIDUEL:"
    const val RAPID_FIRE_PREFIX = "RAPIDFIRE:"

    fun randomIcebreaker(): String = icebreakers.random()
    fun randomEmojiDuel(): String = duelEmojis.random()
    fun randomRapidTopic(): String = rapidTopics.random()

    fun formatIcebreaker(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(100)
        return "${ICEBREAKER_PREFIX}${esc(p)}|${hostLabel} icebreaker"
    }

    fun parseIcebreaker(content: String): String? {
        if (!content.startsWith(ICEBREAKER_PREFIX)) return null
        return unesc(content.removePrefix(ICEBREAKER_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatEmojiDuel(pair: String, hostLabel: String): String {
        val p = pair.trim().ifBlank { randomEmojiDuel() }.take(16)
        return "${EMOJI_DUEL_PREFIX}${esc(p)}|${hostLabel} emoji duel — pick a side!"
    }

    fun parseEmojiDuel(content: String): String? {
        if (!content.startsWith(EMOJI_DUEL_PREFIX)) return null
        return unesc(content.removePrefix(EMOJI_DUEL_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatRapidFire(topic: String, hostLabel: String): String {
        val t = topic.trim().take(40)
        return "${RAPID_FIRE_PREFIX}${esc(t)}|${hostLabel} rapid-fire — name 5 in 20s!"
    }

    fun parseRapidFire(content: String): String? {
        if (!content.startsWith(RAPID_FIRE_PREFIX)) return null
        return unesc(content.removePrefix(RAPID_FIRE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    const val SCATTER_PREFIX = "SCATTER:"
    const val MINUTE_TALK_PREFIX = "MINUTETALK:"
    const val CAPTION_THIS_PREFIX = "CAPTION:"

    fun randomScatter(): Pair<String, String> = scatterLetters.random() to scatterCats.random()
    fun randomTalkTopic(): String = talkTopics.random()
    fun randomCaptionSeed(): String = captionSeeds.random()

    fun formatScatter(letter: String, category: String, hostLabel: String): String {
        val l = letter.trim().take(2).uppercase()
        val c = category.trim().take(24)
        return "${SCATTER_PREFIX}${esc(l)}|${esc(c)}|${hostLabel} scattergories — name one!"
    }

    fun parseScatter(content: String): Pair<String, String>? {
        if (!content.startsWith(SCATTER_PREFIX)) return null
        val body = content.removePrefix(SCATTER_PREFIX)
        val letter = unesc(body.substringBefore('|'))
        val cat = unesc(body.substringAfter('|').substringBefore('|'))
        if (letter.isBlank() || cat.isBlank()) return null
        return letter to cat
    }

    fun formatMinuteTalk(topic: String, hostLabel: String): String {
        val t = topic.trim().take(80)
        return "${MINUTE_TALK_PREFIX}${esc(t)}|${hostLabel} 60s talk — go!"
    }

    fun parseMinuteTalk(content: String): String? {
        if (!content.startsWith(MINUTE_TALK_PREFIX)) return null
        return unesc(content.removePrefix(MINUTE_TALK_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatCaptionThis(seed: String, hostLabel: String): String {
        val s = seed.trim().take(40)
        return "${CAPTION_THIS_PREFIX}${esc(s)}|${hostLabel} caption this!"
    }

    fun parseCaptionThis(content: String): String? {
        if (!content.startsWith(CAPTION_THIS_PREFIX)) return null
        return unesc(content.removePrefix(CAPTION_THIS_PREFIX).substringBefore('|')).ifBlank { null }
    }

    const val STORY_SWAP_PREFIX = "STORYSWAP:"
    const val KARAOKE_PREFIX = "KARAOKE:"
    const val BLIND_Q_PREFIX = "BLINDQ:"

    fun randomStoryOpener(): String = storyOpeners.random()
    fun randomKaraoke(): String = karaokeLines.random()
    fun randomBlindQ(): String = blindQs.random()

    fun formatStorySwap(opener: String, hostLabel: String): String {
        val o = opener.trim().take(80)
        return "${STORY_SWAP_PREFIX}${esc(o)}|${hostLabel} story swap — continue!"
    }

    fun parseStorySwap(content: String): String? {
        if (!content.startsWith(STORY_SWAP_PREFIX)) return null
        return unesc(content.removePrefix(STORY_SWAP_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatKaraoke(line: String, hostLabel: String): String {
        val l = line.trim().take(80)
        return "${KARAOKE_PREFIX}${esc(l)}|${hostLabel} karaoke challenge"
    }

    fun parseKaraoke(content: String): String? {
        if (!content.startsWith(KARAOKE_PREFIX)) return null
        return unesc(content.removePrefix(KARAOKE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatBlindQ(q: String, hostLabel: String): String {
        val qq = q.trim().take(80)
        return "${BLIND_Q_PREFIX}${esc(qq)}|${hostLabel} blind Q — guess about someone!"
    }

    fun parseBlindQ(content: String): String? {
        if (!content.startsWith(BLIND_Q_PREFIX)) return null
        return unesc(content.removePrefix(BLIND_Q_PREFIX).substringBefore('|')).ifBlank { null }
    }

    const val FORTUNE_PREFIX = "FORTUNE:"
    const val EMOJI_QUIZ_PREFIX = "EMOJIQUIZ:"
    const val CHAIN_REACT_PREFIX = "CHAINREACT:"

    fun randomFortune(): String = fortunes.random()
    fun randomEmojiQuiz(): Pair<String, String> = emojiQuiz.random()
    fun randomChainSeed(): String = chainSeeds.random()

    fun formatFortune(text: String, hostLabel: String): String {
        val t = text.trim().take(80)
        return "${FORTUNE_PREFIX}${esc(t)}|${hostLabel} fortune cookie"
    }

    fun parseFortune(content: String): String? {
        if (!content.startsWith(FORTUNE_PREFIX)) return null
        return unesc(content.removePrefix(FORTUNE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatEmojiQuiz(prompt: String, answer: String, hostLabel: String): String {
        val p = prompt.trim().take(24)
        val a = answer.trim().take(40)
        return "${EMOJI_QUIZ_PREFIX}${esc(p)}|${esc(a)}|${hostLabel} emoji quiz"
    }

    fun parseEmojiQuiz(content: String): Pair<String, String>? {
        if (!content.startsWith(EMOJI_QUIZ_PREFIX)) return null
        val body = content.removePrefix(EMOJI_QUIZ_PREFIX)
        if (!body.contains('|')) return null
        val p = unesc(body.substringBefore('|'))
        val a = unesc(body.substringAfter('|').substringBefore('|'))
        if (p.isBlank()) return null
        return p to a
    }

    fun formatChainReact(seed: String, hostLabel: String): String {
        val s = seed.trim().ifBlank { randomChainSeed() }.take(8)
        return "${CHAIN_REACT_PREFIX}${esc(s)}|${hostLabel} chain react — reply with related emoji!"
    }

    fun parseChainReact(content: String): String? {
        if (!content.startsWith(CHAIN_REACT_PREFIX)) return null
        return unesc(content.removePrefix(CHAIN_REACT_PREFIX).substringBefore('|')).ifBlank { null }
    }


    const val DEBATE_PREFIX = "DEBATE:"
    const val MIRROR_PREFIX = "MIRROR:"
    const val HIDESEEK_PREFIX = "HIDESEEK:"
    const val TOAST_PREFIX = "TOAST:"

    fun randomDebateTopic(): String = debateTopics.random()
    fun randomMirrorLine(): String = mirrorLines.random()
    fun randomHideEmoji(): String = hideEmojis.random()
    fun randomToast(): String = roastLines.random()

    fun formatDebate(topic: String, hostLabel: String): String {
        val t = topic.trim().take(80)
        return "${DEBATE_PREFIX}${esc(t)}|${hostLabel} debate — pick a side!"
    }

    fun parseDebate(content: String): String? {
        if (!content.startsWith(DEBATE_PREFIX)) return null
        return unesc(content.removePrefix(DEBATE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatMirror(line: String, hostLabel: String): String {
        val l = line.trim().take(100)
        return "${MIRROR_PREFIX}${esc(l)}|${hostLabel} mirror — repeat in your style"
    }

    fun parseMirror(content: String): String? {
        if (!content.startsWith(MIRROR_PREFIX)) return null
        return unesc(content.removePrefix(MIRROR_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatHideSeek(emoji: String, hostLabel: String): String {
        val e = emoji.trim().ifBlank { randomHideEmoji() }.take(8)
        return "${HIDESEEK_PREFIX}${esc(e)}|${hostLabel} hide & seek — find the emoji in chat history!"
    }

    fun parseHideSeek(content: String): String? {
        if (!content.startsWith(HIDESEEK_PREFIX)) return null
        return unesc(content.removePrefix(HIDESEEK_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatToast(line: String, hostLabel: String): String {
        val t = line.trim().take(80)
        return "${TOAST_PREFIX}${esc(t)}|${hostLabel} friendly roast"
    }

    fun parseToast(content: String): String? {
        if (!content.startsWith(TOAST_PREFIX)) return null
        return unesc(content.removePrefix(TOAST_PREFIX).substringBefore('|')).ifBlank { null }
    }

    const val HOTPOTATO_PREFIX = "HOTPOTATO:"
    const val WORDHINT_PREFIX = "WORDHINT:"

    fun randomHotPotatoSeconds(): Int = potatoSeconds.random()
    fun randomWordHint(): Pair<String, String> = wordHints.random()

    fun formatHotPotato(seconds: Int, hostLabel: String): String {
        val s = seconds.coerceIn(5, 30)
        return "${HOTPOTATO_PREFIX}$s|${hostLabel} hot potato — pass in ${s}s!"
    }

    fun parseHotPotato(content: String): Int? {
        if (!content.startsWith(HOTPOTATO_PREFIX)) return null
        return unesc(content.removePrefix(HOTPOTATO_PREFIX).substringBefore('|')).toIntOrNull()
    }

    fun formatWordHint(hint: String, answer: String, hostLabel: String): String {
        val h = hint.trim().take(60)
        val a = answer.trim().take(40)
        return "${WORDHINT_PREFIX}${esc(h)}|${esc(a)}|${hostLabel} word hint"
    }

    fun parseWordHint(content: String): Pair<String, String>? {
        if (!content.startsWith(WORDHINT_PREFIX)) return null
        val body = content.removePrefix(WORDHINT_PREFIX)
        val h = unesc(body.substringBefore('|'))
        val a = unesc(body.substringAfter('|').substringBefore('|'))
        if (h.isBlank()) return null
        return h to a
    }

    const val SPYFALL_PREFIX = "SPYFALL:"
    const val ACROSTIC_PREFIX = "ACROSTIC:"
    const val EMOJI_TR_PREFIX = "EMOJITR:"

    fun randomSpyLocation(): String = spyLocations.random()
    fun randomAcrostic(): String = acrosticSeeds.random()
    fun randomEmojiTr(): Pair<String, String> = emojiTr.random()

    fun formatSpyfall(location: String, hostLabel: String): String {
        val loc = location.trim().take(40)
        return "${SPYFALL_PREFIX}${esc(loc)}|${hostLabel} spyfall — one spy doesn't know the place!"
    }

    fun parseSpyfall(content: String): String? {
        if (!content.startsWith(SPYFALL_PREFIX)) return null
        return unesc(content.removePrefix(SPYFALL_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatAcrostic(seed: String, hostLabel: String): String {
        val s = seed.trim().take(20)
        return "${ACROSTIC_PREFIX}${esc(s)}|${hostLabel} acrostic — start each line with letters of '$s'"
    }

    fun parseAcrostic(content: String): String? {
        if (!content.startsWith(ACROSTIC_PREFIX)) return null
        return unesc(content.removePrefix(ACROSTIC_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatEmojiTranslate(prompt: String, answer: String, hostLabel: String): String {
        val p = prompt.trim().take(24)
        val a = answer.trim().take(40)
        return "${EMOJI_TR_PREFIX}${esc(p)}|${esc(a)}|${hostLabel} emoji translate"
    }

    fun parseEmojiTranslate(content: String): Pair<String, String>? {
        if (!content.startsWith(EMOJI_TR_PREFIX)) return null
        val body = content.removePrefix(EMOJI_TR_PREFIX)
        val p = unesc(body.substringBefore('|'))
        val a = unesc(body.substringAfter('|').substringBefore('|'))
        if (p.isBlank()) return null
        return p to a
    }

    const val TWENTYQ_PREFIX = "TWENTYQ:"
    const val RHYME_PREFIX = "RHYME:"
    const val ODDONE_PREFIX = "ODDONE:"

    fun randomTwentySubject(): String = twentySubjects.random()
    fun randomRhymeSeed(): String = rhymeSeeds.random()
    fun randomOddOne(): Pair<String, String> = oddSets.random()

    fun formatTwentyQuestions(subject: String, hostLabel: String): String {
        val s = subject.trim().take(40)
        return "${TWENTYQ_PREFIX}${esc(s)}|${hostLabel} 20 questions — yes/no only!"
    }

    fun parseTwentyQuestions(content: String): String? {
        if (!content.startsWith(TWENTYQ_PREFIX)) return null
        return unesc(content.removePrefix(TWENTYQ_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatRhyme(seed: String, hostLabel: String): String {
        val s = seed.trim().take(20)
        return "${RHYME_PREFIX}${esc(s)}|${hostLabel} rhyme chain — rhyme with '$s'"
    }

    fun parseRhyme(content: String): String? {
        if (!content.startsWith(RHYME_PREFIX)) return null
        return unesc(content.removePrefix(RHYME_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatOddOneOut(options: String, answer: String, hostLabel: String): String {
        val o = options.trim().take(80)
        val a = answer.trim().take(40)
        return "${ODDONE_PREFIX}${esc(o)}|${esc(a)}|${hostLabel} odd one out"
    }

    fun parseOddOneOut(content: String): Pair<String, String>? {
        if (!content.startsWith(ODDONE_PREFIX)) return null
        val body = content.removePrefix(ODDONE_PREFIX)
        val o = unesc(body.substringBefore('|'))
        val a = unesc(body.substringAfter('|').substringBefore('|'))
        if (o.isBlank()) return null
        return o to a
    }

    const val CATEGORIES_PREFIX = "CATEGORIES:"
    const val PASSWORD_PREFIX = "PASSWORD:"
    const val TIMECAPSULE_PREFIX = "TIMECAPSULE:"

    fun randomCategory(): String = categories.random()
    fun randomPasswordHint(): String = passwordHints.random()
    fun randomCapsule(): String = capsules.random()

    fun formatCategories(cat: String, hostLabel: String): String {
        val c = cat.trim().take(30)
        return "${CATEGORIES_PREFIX}${esc(c)}|${hostLabel} categories — name things in '$c'"
    }

    fun parseCategories(content: String): String? {
        if (!content.startsWith(CATEGORIES_PREFIX)) return null
        return unesc(content.removePrefix(CATEGORIES_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatPasswordGame(hint: String, hostLabel: String): String {
        val h = hint.trim().take(40)
        return "${PASSWORD_PREFIX}${esc(h)}|${hostLabel} password game — guess under rules"
    }

    fun parsePasswordGame(content: String): String? {
        if (!content.startsWith(PASSWORD_PREFIX)) return null
        return unesc(content.removePrefix(PASSWORD_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatTimeCapsule(note: String, hostLabel: String): String {
        val n = note.trim().take(80)
        return "${TIMECAPSULE_PREFIX}${esc(n)}|${hostLabel} time capsule — open later"
    }

    fun parseTimeCapsule(content: String): String? {
        if (!content.startsWith(TIMECAPSULE_PREFIX)) return null
        return unesc(content.removePrefix(TIMECAPSULE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    const val TABOO_PREFIX = "TABOO:"
    const val LIGHTNING_PREFIX = "LIGHTNING:"
    const val TWO_WORDS_PREFIX = "TWOWORDS:"

    fun randomTaboo(): String = tabooCards.random()
    fun randomLightning(): String = lightningPrompts.random()
    fun randomTwoWords(): String = twoWordSeeds.random()

    fun formatTaboo(card: String, hostLabel: String): String {
        val c = card.trim().take(60)
        val word = c.substringBefore('|')
        return "${TABOO_PREFIX}${esc(c)}|${hostLabel} taboo — describe '$word' without banned words"
    }

    fun parseTaboo(content: String): String? {
        if (!content.startsWith(TABOO_PREFIX)) return null
        return unesc(content.removePrefix(TABOO_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatLightning(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(60)
        return "${LIGHTNING_PREFIX}${esc(p)}|${hostLabel} lightning round"
    }

    fun parseLightning(content: String): String? {
        if (!content.startsWith(LIGHTNING_PREFIX)) return null
        return unesc(content.removePrefix(LIGHTNING_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatTwoWords(seed: String, hostLabel: String): String {
        val s = seed.trim().take(20)
        return "${TWO_WORDS_PREFIX}${esc(s)}|${hostLabel} two-word story — start with '$s'"
    }

    fun parseTwoWords(content: String): String? {
        if (!content.startsWith(TWO_WORDS_PREFIX)) return null
        return unesc(content.removePrefix(TWO_WORDS_PREFIX).substringBefore('|')).ifBlank { null }
    }

    const val WHISPER_PREFIX = "WHISPER:"
    const val COUNTDOWN_RACE_PREFIX = "COUNTRACE:"

    fun randomWhisper(): String = whisperPrompts.random()
    fun randomCountdownRace(): Int = countdownRaceSeeds.random()

    fun formatWhisper(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(60)
        return "${WHISPER_PREFIX}${esc(p)}|${hostLabel} whisper challenge"
    }

    fun parseWhisper(content: String): String? {
        if (!content.startsWith(WHISPER_PREFIX)) return null
        return unesc(content.removePrefix(WHISPER_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatCountdownRace(seconds: Int, hostLabel: String): String {
        val s = seconds.coerceIn(2, 30)
        return "${COUNTDOWN_RACE_PREFIX}$s|${hostLabel} countdown race - first reply wins!"
    }

    fun parseCountdownRace(content: String): String? {
        if (!content.startsWith(COUNTDOWN_RACE_PREFIX)) return null
        return unesc(content.removePrefix(COUNTDOWN_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    const val EMOJI_MEMORY_PREFIX = "EMOJIMEM:"
    const val GEO_GUESS_PREFIX = "GEOGUESS:"

    fun randomEmojiMemory(): String = emojiMemoryBoards.random()
    fun randomGeoClue(): String = geoClues.random()

    fun formatEmojiMemory(board: String, hostLabel: String): String {
        val b = board.trim().take(24)
        return "${EMOJI_MEMORY_PREFIX}${esc(b)}|${hostLabel} emoji memory — memorize then recall"
    }

    fun parseEmojiMemory(content: String): String? {
        if (!content.startsWith(EMOJI_MEMORY_PREFIX)) return null
        return unesc(content.removePrefix(EMOJI_MEMORY_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatGeoGuess(clue: String, hostLabel: String): String {
        val c = clue.trim().take(50)
        return "${GEO_GUESS_PREFIX}${esc(c)}|${hostLabel} geo guess"
    }

    fun parseGeoGuess(content: String): String? {
        if (!content.startsWith(GEO_GUESS_PREFIX)) return null
        return unesc(content.removePrefix(GEO_GUESS_PREFIX).substringBefore('|')).ifBlank { null }
    }

    const val ONE_WORD_PREFIX = "ONEWORD:"
    const val SPEED_MATH_PREFIX = "SPEEDMATH:"
    const val STORY_SEED_PREFIX = "STORYSEED:"

    fun randomOneWord(): String = oneWords.random()
    fun randomMathQ(): String = mathQs.random()
    fun randomStorySeed(): String = storySeeds.random()

    fun formatOneWord(word: String, hostLabel: String): String {
        val w = word.trim().take(20)
        return "${ONE_WORD_PREFIX}${esc(w)}|${hostLabel} one-word story — continue with one word"
    }

    fun parseOneWord(content: String): String? {
        if (!content.startsWith(ONE_WORD_PREFIX)) return null
        return unesc(content.removePrefix(ONE_WORD_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatSpeedMath(q: String, hostLabel: String): String {
        val qq = q.trim().take(20)
        return "${SPEED_MATH_PREFIX}${esc(qq)}|${hostLabel} speed math — first correct wins"
    }

    fun parseSpeedMath(content: String): String? {
        if (!content.startsWith(SPEED_MATH_PREFIX)) return null
        return unesc(content.removePrefix(SPEED_MATH_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatStorySeed(seed: String, hostLabel: String): String {
        val s = seed.trim().take(50)
        return "${STORY_SEED_PREFIX}${esc(s)}|${hostLabel} story seed — write the next sentence"
    }

    fun parseStorySeed(content: String): String? {
        if (!content.startsWith(STORY_SEED_PREFIX)) return null
        return unesc(content.removePrefix(STORY_SEED_PREFIX).substringBefore('|')).ifBlank { null }
    }

    const val WOULD_YOU_PREFIX2 = "WOULD2:"
    const val EMOJI_ONLY_PREFIX = "EMOJIONLY:"
    const val BLIND_DRAW_PREFIX = "BLINDDRAW:"

    fun randomWould2(): Pair<String, String> = wouldPairs2.random()
    fun randomEmojiOnly(): String = emojiOnlyPrompts.random()
    fun randomBlindDraw(): String = blindDraws.random()

    fun formatWould2(a: String, b: String, hostLabel: String): String {
        return "${WOULD_YOU_PREFIX2}${esc(a.trim().take(30))}|${esc(b.trim().take(30))}|${hostLabel} would you rather"
    }

    fun parseWould2(content: String): Pair<String, String>? {
        if (!content.startsWith(WOULD_YOU_PREFIX2)) return null
        val body = content.removePrefix(WOULD_YOU_PREFIX2)
        val a = unesc(body.substringBefore('|'))
        val b = unesc(body.substringAfter('|').substringBefore('|'))
        if (a.isBlank() || b.isBlank()) return null
        return a to b
    }

    fun formatEmojiOnly(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(50)
        return "${EMOJI_ONLY_PREFIX}${esc(p)}|${hostLabel} emoji-only challenge"
    }

    fun parseEmojiOnly(content: String): String? {
        if (!content.startsWith(EMOJI_ONLY_PREFIX)) return null
        return unesc(content.removePrefix(EMOJI_ONLY_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatBlindDraw(token: String, hostLabel: String): String {
        val t = token.trim().take(8)
        return "${BLIND_DRAW_PREFIX}${esc(t)}|${hostLabel} blind draw — guess the emoji"
    }

    fun parseBlindDraw(content: String): String? {
        if (!content.startsWith(BLIND_DRAW_PREFIX)) return null
        return unesc(content.removePrefix(BLIND_DRAW_PREFIX).substringBefore('|')).ifBlank { null }
    }


    const val ALPHABET_RACE_PREFIX = "ALPHARACE:"
    const val SILENT_MOVIE_PREFIX = "SILENTMOVIE:"
    const val COLOR_WORD_PREFIX = "COLORWORD:"

    fun randomAlphabetStart(): String = alphabetStarts.random()
    fun randomSilentMovie(): String = silentMovies.random()
    fun randomColorWord(): String = colorWords.random()

    fun formatAlphabetRace(start: String, hostLabel: String): String {
        val s = start.trim().take(4)
        return "${ALPHABET_RACE_PREFIX}${esc(s)}|${hostLabel} alphabet race"
    }

    fun parseAlphabetRace(content: String): String? {
        if (!content.startsWith(ALPHABET_RACE_PREFIX)) return null
        return unesc(content.removePrefix(ALPHABET_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatSilentMovie(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(40)
        return "${SILENT_MOVIE_PREFIX}${esc(p)}|${hostLabel} silent movie"
    }

    fun parseSilentMovie(content: String): String? {
        if (!content.startsWith(SILENT_MOVIE_PREFIX)) return null
        return unesc(content.removePrefix(SILENT_MOVIE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatColorWord(pair: String, hostLabel: String): String {
        val p = pair.trim().take(30)
        return "${COLOR_WORD_PREFIX}${esc(p)}|${hostLabel} color-word"
    }

    fun parseColorWord(content: String): String? {
        if (!content.startsWith(COLOR_WORD_PREFIX)) return null
        return unesc(content.removePrefix(COLOR_WORD_PREFIX).substringBefore('|')).ifBlank { null }
    }


    const val DEBATE_FLASH_PREFIX = "DEBATEFLASH:"
    const val QUICK_POLL_PREFIX = "QUICKPOLL:"

    fun randomDebateFlash(): String = debateFlashTopics.random()
    fun randomEmojiStory(): String = emojiStories.random()
    fun randomQuickPoll(): String = quickPolls.random()

    fun formatDebateFlash(topic: String, hostLabel: String): String {
        val tp = topic.trim().take(50)
        return "${DEBATE_FLASH_PREFIX}${esc(tp)}|${hostLabel} debate flash - 30s side"
    }

    fun parseDebateFlash(content: String): String? {
        if (!content.startsWith(DEBATE_FLASH_PREFIX)) return null
        return unesc(content.removePrefix(DEBATE_FLASH_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatQuickPoll(options: String, hostLabel: String): String {
        val o = options.trim().take(60)
        return "${QUICK_POLL_PREFIX}${esc(o)}|${hostLabel} quick poll"
    }

    fun parseQuickPoll(content: String): String? {
        if (!content.startsWith(QUICK_POLL_PREFIX)) return null
        return unesc(content.removePrefix(QUICK_POLL_PREFIX).substringBefore('|')).ifBlank { null }
    }


    const val MIRROR_ECHO_PREFIX = "MIRRORECHO:"
    const val SYNC_CLAP_PREFIX = "SYNCCLAP:"
    const val FACT_OR_FICTION_PREFIX = "FACTORFICTION:"

    fun randomMirrorEcho(): String = mirrorEchoLines.random()
    fun randomSyncClap(): String = clapCounts.random()
    fun randomFactOrFiction(): String = facts.random()

    fun formatMirrorEcho(line: String, hostLabel: String): String {
        val l = line.trim().take(50)
        return "${MIRROR_ECHO_PREFIX}${esc(l)}|${hostLabel} mirror echo — reverse it"
    }

    fun parseMirrorEcho(content: String): String? {
        if (!content.startsWith(MIRROR_ECHO_PREFIX)) return null
        return unesc(content.removePrefix(MIRROR_ECHO_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatSyncClap(count: String, hostLabel: String): String {
        val c = count.trim().take(4)
        return "${SYNC_CLAP_PREFIX}${esc(c)}|${hostLabel} sync clap x$c"
    }

    fun parseSyncClap(content: String): String? {
        if (!content.startsWith(SYNC_CLAP_PREFIX)) return null
        return unesc(content.removePrefix(SYNC_CLAP_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatFactOrFiction(item: String, hostLabel: String): String {
        val it = item.trim().take(60)
        return "${FACT_OR_FICTION_PREFIX}${esc(it)}|${hostLabel} fact or fiction"
    }

    fun parseFactOrFiction(content: String): String? {
        if (!content.startsWith(FACT_OR_FICTION_PREFIX)) return null
        return unesc(content.removePrefix(FACT_OR_FICTION_PREFIX).substringBefore('|')).ifBlank { null }
    }


    const val IMPULSE_DRAW_PREFIX = "IMPULSEDRAW:"
    const val WORD_SCRAMBLE_PREFIX = "WORDSCRAMBLE:"
    const val REACTION_DUEL_PREFIX = "REACTDUEL:"

    fun randomImpulseDraw(): String = impulseDraws.random()
    fun randomWordScramble(): String = scrambles.random()
    fun randomReactionDuel(): String = reactionDuels.random()

    fun formatImpulseDraw(token: String, hostLabel: String): String {
        val tk = token.trim().take(8)
        return "${IMPULSE_DRAW_PREFIX}${esc(tk)}|${hostLabel} impulse draw"
    }

    fun parseImpulseDraw(content: String): String? {
        if (!content.startsWith(IMPULSE_DRAW_PREFIX)) return null
        return unesc(content.removePrefix(IMPULSE_DRAW_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatWordScramble(pair: String, hostLabel: String): String {
        val p = pair.trim().take(40)
        return "${WORD_SCRAMBLE_PREFIX}${esc(p)}|${hostLabel} word scramble"
    }

    fun parseWordScramble(content: String): String? {
        if (!content.startsWith(WORD_SCRAMBLE_PREFIX)) return null
        return unesc(content.removePrefix(WORD_SCRAMBLE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatReactionDuel(pair: String, hostLabel: String): String {
        val p = pair.trim().take(20)
        return "${REACTION_DUEL_PREFIX}${esc(p)}|${hostLabel} reaction duel"
    }

    fun parseReactionDuel(content: String): String? {
        if (!content.startsWith(REACTION_DUEL_PREFIX)) return null
        return unesc(content.removePrefix(REACTION_DUEL_PREFIX).substringBefore('|')).ifBlank { null }
    }


    const val CODE_BREAKER_PREFIX = "CODEBREAKER:"
    const val SILLY_LAW_PREFIX = "SILLYLAW:"
    const val EMOJI_MATH_PREFIX = "EMOJIMATH:"

    fun randomCodeBreaker(): String = codes.random()
    fun randomSillyLaw(): String = sillyLaws.random()
    fun randomEmojiMath(): String = emojiMaths.random()

    fun formatCodeBreaker(code: String, hostLabel: String): String {
        val c = code.trim().take(8)
        return "${CODE_BREAKER_PREFIX}${esc(c)}|${hostLabel} code breaker — guess digits"
    }

    fun parseCodeBreaker(content: String): String? {
        if (!content.startsWith(CODE_BREAKER_PREFIX)) return null
        return unesc(content.removePrefix(CODE_BREAKER_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatSillyLaw(law: String, hostLabel: String): String {
        val l = law.trim().take(50)
        return "${SILLY_LAW_PREFIX}${esc(l)}|${hostLabel} silly law"
    }

    fun parseSillyLaw(content: String): String? {
        if (!content.startsWith(SILLY_LAW_PREFIX)) return null
        return unesc(content.removePrefix(SILLY_LAW_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatEmojiMath(expr: String, hostLabel: String): String {
        val e = expr.trim().take(20)
        return "${EMOJI_MATH_PREFIX}${esc(e)}|${hostLabel} emoji math"
    }

    fun parseEmojiMath(content: String): String? {
        if (!content.startsWith(EMOJI_MATH_PREFIX)) return null
        return unesc(content.removePrefix(EMOJI_MATH_PREFIX).substringBefore('|')).ifBlank { null }
    }


    const val PIN_THE_MOOD_PREFIX = "PINTHEMOOD:"
    const val REVOKE_RUSH_PREFIX = "REVOKERUSH:"
    const val SECRET_SIGNAL_PREFIX = "SECRETSIGNAL:"

    fun randomPinTheMood(): String = moods.random()
    fun randomRevokeRush(): String = rushWindows.random()
    fun randomSecretSignal(): String = secretSignals.random()

    fun formatPinTheMood(mood: String, hostLabel: String): String {
        val m = mood.trim().take(20)
        return "${PIN_THE_MOOD_PREFIX}${esc(m)}|${hostLabel} pin the mood"
    }

    fun parsePinTheMood(content: String): String? {
        if (!content.startsWith(PIN_THE_MOOD_PREFIX)) return null
        return unesc(content.removePrefix(PIN_THE_MOOD_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatRevokeRush(window: String, hostLabel: String): String {
        val w = window.trim().take(10)
        return "${REVOKE_RUSH_PREFIX}${esc(w)}|${hostLabel} revoke rush"
    }

    fun parseRevokeRush(content: String): String? {
        if (!content.startsWith(REVOKE_RUSH_PREFIX)) return null
        return unesc(content.removePrefix(REVOKE_RUSH_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatSecretSignal(signal: String, hostLabel: String): String {
        val s = signal.trim().take(30)
        return "${SECRET_SIGNAL_PREFIX}${esc(s)}|${hostLabel} secret signal"
    }

    fun parseSecretSignal(content: String): String? {
        if (!content.startsWith(SECRET_SIGNAL_PREFIX)) return null
        return unesc(content.removePrefix(SECRET_SIGNAL_PREFIX).substringBefore('|')).ifBlank { null }
    }



    const val IDEA_RELAY_PREFIX = "IDEARELAY:"
    const val TEMPO_TAP_PREFIX = "TEMPOTAP:"
    const val TRANSLATE_RELAY_PREFIX = "TRANSRELAY:"

    fun randomIdeaRelay(): String = ideaSeeds.random()
    fun randomTempoTap(): String = tempoBeats.random()
    fun randomTranslateRelay(): String = translatePairs.random()

    fun formatIdeaRelay(seed: String, hostLabel: String): String {
        val s = seed.trim().take(40)
        return "${IDEA_RELAY_PREFIX}${esc(s)}|${hostLabel} idea relay"
    }

    fun parseIdeaRelay(content: String): String? {
        if (!content.startsWith(IDEA_RELAY_PREFIX)) return null
        return unesc(content.removePrefix(IDEA_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatTempoTap(beat: String, hostLabel: String): String {
        val b = beat.trim().take(12)
        return "${TEMPO_TAP_PREFIX}${esc(b)}|${hostLabel} tempo tap"
    }

    fun parseTempoTap(content: String): String? {
        if (!content.startsWith(TEMPO_TAP_PREFIX)) return null
        return unesc(content.removePrefix(TEMPO_TAP_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatTranslateRelay(pair: String, hostLabel: String): String {
        val p = pair.trim().take(20)
        return "${TRANSLATE_RELAY_PREFIX}${esc(p)}|${hostLabel} translate relay"
    }

    fun parseTranslateRelay(content: String): String? {
        if (!content.startsWith(TRANSLATE_RELAY_PREFIX)) return null
        return unesc(content.removePrefix(TRANSLATE_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }



    const val INVITE_RACE_PREFIX = "INVITERACE:"
    const val MENTION_MAYHEM_PREFIX = "MENTIONMAY:"
    const val LINK_HUNT_PREFIX = "LINKHUNT:"

    fun randomInviteRace(): String = inviteRaces.random()
    fun randomMentionMayhem(): String = mentionModes.random()
    fun randomLinkHunt(): String = linkHunts.random()

    fun formatInviteRace(mode: String, hostLabel: String): String {
        val m = mode.trim().take(30)
        return "${INVITE_RACE_PREFIX}${esc(m)}|${hostLabel} invite race"
    }

    fun parseInviteRace(content: String): String? {
        if (!content.startsWith(INVITE_RACE_PREFIX)) return null
        return unesc(content.removePrefix(INVITE_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatMentionMayhem(mode: String, hostLabel: String): String {
        val m = mode.trim().take(30)
        return "${MENTION_MAYHEM_PREFIX}${esc(m)}|${hostLabel} mention mayhem"
    }

    fun parseMentionMayhem(content: String): String? {
        if (!content.startsWith(MENTION_MAYHEM_PREFIX)) return null
        return unesc(content.removePrefix(MENTION_MAYHEM_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatLinkHunt(mode: String, hostLabel: String): String {
        val m = mode.trim().take(30)
        return "${LINK_HUNT_PREFIX}${esc(m)}|${hostLabel} link hunt"
    }

    fun parseLinkHunt(content: String): String? {
        if (!content.startsWith(LINK_HUNT_PREFIX)) return null
        return unesc(content.removePrefix(LINK_HUNT_PREFIX).substringBefore('|')).ifBlank { null }
    }


    const val NUDGE_DASH_PREFIX = "NUDGEDASH:"
    const val CODE_CHECK_PREFIX = "CODECHECK:"
    const val TRUST_SPRINT_PREFIX = "TRUSTSPRINT:"

    fun randomNudgeDash(): String = nudgeDashes.random()
    fun randomCodeCheck(): String = codeChecks.random()
    fun randomTrustSprint(): String = trustSprints.random()

    fun formatNudgeDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${NUDGE_DASH_PREFIX}${esc(m)}|${hostLabel} nudge dash"
    }

    fun parseNudgeDash(content: String): String? {
        if (!content.startsWith(NUDGE_DASH_PREFIX)) return null
        return unesc(content.removePrefix(NUDGE_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatCodeCheck(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${CODE_CHECK_PREFIX}${esc(m)}|${hostLabel} code check"
    }

    fun parseCodeCheck(content: String): String? {
        if (!content.startsWith(CODE_CHECK_PREFIX)) return null
        return unesc(content.removePrefix(CODE_CHECK_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatTrustSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${TRUST_SPRINT_PREFIX}${esc(m)}|${hostLabel} trust sprint"
    }

    fun parseTrustSprint(content: String): String? {
        if (!content.startsWith(TRUST_SPRINT_PREFIX)) return null
        return unesc(content.removePrefix(TRUST_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }










    const val MOOD_METER_PREFIX = "MOODMETER:"
    const val FOCUS_SPRINT_PREFIX = "FOCUSPRINT:"
    const val GRATITUDE_ROUND_PREFIX = "GRATROUND:"

    fun randomMoodMeter(): String = moodMeters.random()
    fun randomFocusSprint(): String = focusSprints.random()
    fun randomGratitudeRound(): String = gratitudePrompts.random()

    fun formatMoodMeter(scale: String, hostLabel: String): String {
        val s = scale.trim().take(30)
        return "${MOOD_METER_PREFIX}${esc(s)}|${hostLabel} mood meter"
    }

    fun parseMoodMeter(content: String): String? {
        if (!content.startsWith(MOOD_METER_PREFIX)) return null
        return unesc(content.removePrefix(MOOD_METER_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatFocusSprint(window: String, hostLabel: String): String {
        val w = window.trim().take(10)
        return "${FOCUS_SPRINT_PREFIX}${esc(w)}|${hostLabel} focus sprint"
    }

    fun parseFocusSprint(content: String): String? {
        if (!content.startsWith(FOCUS_SPRINT_PREFIX)) return null
        return unesc(content.removePrefix(FOCUS_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatGratitudeRound(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(40)
        return "${GRATITUDE_ROUND_PREFIX}${esc(p)}|${hostLabel} gratitude round"
    }

    fun parseGratitudeRound(content: String): String? {
        if (!content.startsWith(GRATITUDE_ROUND_PREFIX)) return null
        return unesc(content.removePrefix(GRATITUDE_ROUND_PREFIX).substringBefore('|')).ifBlank { null }
    }


    const val QR_QUEST_PREFIX = "QRQUEST:"
    const val CONTACT_SWAP_PREFIX = "CONTACTSWAP:"
    const val SCAN_SPRINT_PREFIX = "SCANSPRINT:"

    fun randomQrQuest(): String = qrQuests.random()
    fun randomContactSwap(): String = contactSwaps.random()
    fun randomScanSprint(): String = scanSprints.random()

    fun formatQrQuest(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${QR_QUEST_PREFIX}${esc(m)}|${hostLabel} qr quest"
    }

    fun parseQrQuest(content: String): String? {
        if (!content.startsWith(QR_QUEST_PREFIX)) return null
        return unesc(content.removePrefix(QR_QUEST_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatContactSwap(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${CONTACT_SWAP_PREFIX}${esc(m)}|${hostLabel} contact swap"
    }

    fun parseContactSwap(content: String): String? {
        if (!content.startsWith(CONTACT_SWAP_PREFIX)) return null
        return unesc(content.removePrefix(CONTACT_SWAP_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatScanSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${SCAN_SPRINT_PREFIX}${esc(m)}|${hostLabel} scan sprint"
    }

    fun parseScanSprint(content: String): String? {
        if (!content.startsWith(SCAN_SPRINT_PREFIX)) return null
        return unesc(content.removePrefix(SCAN_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }



    const val SPOILER_RACE_PREFIX = "SPOILERRACE:"
    const val BLUR_BATTLE_PREFIX = "BLURBATTLE:"
    const val DOWNLOAD_DASH_PREFIX = "DLDASH:"

    fun randomSpoilerRace(): String = spoilerRaces.random()
    fun randomBlurBattle(): String = blurBattles.random()
    fun randomDownloadDash(): String = downloadDashes.random()

    fun formatSpoilerRace(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${SPOILER_RACE_PREFIX}${esc(m)}|${hostLabel} spoiler race"
    }
    fun parseSpoilerRace(content: String): String? {
        if (!content.startsWith(SPOILER_RACE_PREFIX)) return null
        return unesc(content.removePrefix(SPOILER_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatBlurBattle(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${BLUR_BATTLE_PREFIX}${esc(m)}|${hostLabel} blur battle"
    }
    fun parseBlurBattle(content: String): String? {
        if (!content.startsWith(BLUR_BATTLE_PREFIX)) return null
        return unesc(content.removePrefix(BLUR_BATTLE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatDownloadDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${DOWNLOAD_DASH_PREFIX}${esc(m)}|${hostLabel} download dash"
    }
    fun parseDownloadDash(content: String): String? {
        if (!content.startsWith(DOWNLOAD_DASH_PREFIX)) return null
        return unesc(content.removePrefix(DOWNLOAD_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }



    const val PIN_DROP_PREFIX = "PINDROP:"
    const val FILE_RELAY_PREFIX = "FILERELAY:"
    const val MAP_DASH_PREFIX = "MAPDASH:"
    const val VAULT_LOCK_PREFIX = "VAULTLOCK:"
    const val WATERMARK_HUNT_PREFIX = "WMHUNT:"
    const val SECURE_SPRINT_PREFIX = "SECURESPRINT:"

    fun randomPinDrop(): String = pinDrops.random()
    fun randomFileRelay(): String = fileRelays.random()
    fun randomMapDash(): String = mapDashes.random()
    fun randomVaultLock(): String = vaultLocks.random()
    fun randomWatermarkHunt(): String = wmHunts.random()
    fun randomSecureSprint(): String = secureSprints.random()

    fun formatPinDrop(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${PIN_DROP_PREFIX}${esc(m)}|${hostLabel} pin drop"
    }
    fun parsePinDrop(content: String): String? {
        if (!content.startsWith(PIN_DROP_PREFIX)) return null
        return unesc(content.removePrefix(PIN_DROP_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatFileRelay(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${FILE_RELAY_PREFIX}${esc(m)}|${hostLabel} file relay"
    }
    fun parseFileRelay(content: String): String? {
        if (!content.startsWith(FILE_RELAY_PREFIX)) return null
        return unesc(content.removePrefix(FILE_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatMapDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${MAP_DASH_PREFIX}${esc(m)}|${hostLabel} map dash"
    }
    fun parseMapDash(content: String): String? {
        if (!content.startsWith(MAP_DASH_PREFIX)) return null
        return unesc(content.removePrefix(MAP_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatVaultLock(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${VAULT_LOCK_PREFIX}${esc(m)}|${hostLabel} vault lock"
    }
    fun parseVaultLock(content: String): String? {
        if (!content.startsWith(VAULT_LOCK_PREFIX)) return null
        return unesc(content.removePrefix(VAULT_LOCK_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatWatermarkHunt(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${WATERMARK_HUNT_PREFIX}${esc(m)}|${hostLabel} watermark hunt"
    }
    fun parseWatermarkHunt(content: String): String? {
        if (!content.startsWith(WATERMARK_HUNT_PREFIX)) return null
        return unesc(content.removePrefix(WATERMARK_HUNT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatSecureSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${SECURE_SPRINT_PREFIX}${esc(m)}|${hostLabel} secure sprint"
    }
    fun parseSecureSprint(content: String): String? {
        if (!content.startsWith(SECURE_SPRINT_PREFIX)) return null
        return unesc(content.removePrefix(SECURE_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }



    const val PHOTO_RACE_PREFIX = "PHOTORACE:"
    const val CLIP_DASH_PREFIX = "CLIPDASH:"
    const val FRAME_HUNT_PREFIX = "FRAMEHUNT:"
    const val SUMMARY_CIRCLE_PREFIX = "SUMCIRCLE:"
    const val REWRITE_RELAY_PREFIX = "REWRITERELAY:"
    const val PROMPT_SPRINT_PREFIX = "PROMPTSPRINT:"
    const val SUGGEST_CIRCLE_PREFIX = "SUGCIRCLE:"
    const val VOICE_RACE_PREFIX = "VOICERACE:"
    const val REPLY_SPRINT_PREFIX = "REPLYSPRINT:"
    const val PIXEL_QUEST_PREFIX = "PIXELQUEST:"
    const val ASSIST_CIRCLE_PREFIX = "ASSISTCIRCLE:"
    const val DECISION_DASH_PREFIX = "DECISIONDASH:"
    const val DOC_HUNT_PREFIX = "DOCHUNT:"
    const val MEANING_RACE_PREFIX = "MEANINGRACE:"
    const val INSIGHT_SPRINT_PREFIX = "INSIGHTSPRINT:"
    const val GIF_RELAY_PREFIX = "GIFRELAY:"
    const val MARK_HUNT_PREFIX = "MARKHUNT:"
    const val LEAK_SPRINT_PREFIX = "LEAKSPRINT:"
    const val VOICE_RING_PREFIX = "VOICERING:"
    const val VIDEO_STAGE_PREFIX = "VIDEOSTAGE:"
    const val RING_DASH_PREFIX = "RINGDASH:"
    const val WALL_PICK_PREFIX = "WALLPICK:"
    const val FONT_RACE_PREFIX = "FONTRACE:"
    const val THEME_SPRINT_PREFIX = "THEMESPRINT:"
    const val UNREAD_RUSH_PREFIX = "UNREADRUSH:"
    const val RING_CHOIR_PREFIX = "RINGCHOIR:"
    const val ALERT_SPRINT_PREFIX = "ALERTSPRINT:"
    const val SOUND_WAVE_PREFIX = "SOUNDWAVE:"
    const val PREVIEW_MASK_PREFIX = "PREVIEWMASK:"
    const val BEEP_DASH_PREFIX = "BEEPDASH:"
    const val PUSH_RACE_PREFIX = "PUSHRACE:"
    const val REMIND_CIRCLE_PREFIX = "REMINDCIRCLE:"
    const val WAKE_SPRINT_PREFIX = "WAKESPRINT:"
    const val QUIET_HOUR_PREFIX = "QUIETHOUR:"
    const val OFFLINE_HINT_PREFIX = "OFFLINEHINT:"
    const val FALLBACK_DASH_PREFIX = "FALLBACKDASH:"
    const val CLICK_BEAT_PREFIX = "CLICKBEAT:"
    const val BUZZ_RELAY_PREFIX = "BUZZRELAY:"
    const val FEEL_SPRINT_PREFIX = "FEELSPRINT:"
    const val SLIDE_RACE_PREFIX = "SLIDERACE:"
    const val FADE_CIRCLE_PREFIX = "FADECIRCLE:"
    const val SPRING_DASH_PREFIX = "SPRINGDASH:"
    const val SNAP_GUARD_PREFIX = "SNAPGUARD:"
    const val RECENTS_HIDE_PREFIX = "RECENTSHIDE:"
    const val SHIELD_SPRINT_PREFIX = "SHIELDSPRINT:"
    const val COPY_LOCK_PREFIX = "COPYLOCK:"
    const val EXPORT_SEAL_PREFIX = "EXPORTSEAL:"
    const val LEAK_WALL_PREFIX = "LEAKWALL:"
    const val FORWARD_SEAL_PREFIX = "FORWARDSEAL:"
    const val CHAT_EXPORT_LOCK_PREFIX = "CHATEXPORTLOCK:"
    const val VAULT_FENCE_PREFIX = "VAULTFENCE:"
    const val SEAL_SPRINT_PREFIX = "SEALSPRINT:"
    const val PQXDH_DASH_PREFIX = "PQXDHDASH:"
    const val CERT_RELAY_PREFIX = "CERTRELAY:"
    const val MARK_SPRINT_PREFIX = "MARKSPRINT:"
    const val FADE_TIMER_PREFIX = "FADETIMER:"
    const val STAMP_RELAY_PREFIX = "STAMPRELAY:"
    const val LINK_LOCK_PREFIX = "LINKLOCK:"
    const val PREVIEW_MUTE_PREFIX = "PREVIEWMUTE:"
    const val URL_FENCE_PREFIX = "URLFENCE:"
    const val NOTIF_MASK_PREFIX = "NOTIFMASK:"
    const val LIST_BLUR_PREFIX = "LISTBLUR:"
    const val TRAY_SEAL_PREFIX = "TRAYSEAL:"
    const val REACT_LOCK_PREFIX = "REACTLOCK:"
    const val STAR_SEAL_PREFIX = "STARSEAL:"
    const val META_FENCE_PREFIX = "METAFENCE:"
    const val TYPING_SEAL_PREFIX = "TYPINGSEAL:"
    const val READ_SEAL_PREFIX = "READSEAL:"
    const val PRESENCE_SEAL_PREFIX = "PRESENCESEAL:"
    const val LASTSEEN_SEAL_PREFIX = "LASTSEENSEAL:"

    fun randomPhotoRace(): String = photoRaces.random()
    fun randomClipDash(): String = clipDashes.random()
    fun randomFrameHunt(): String = frameHunts.random()
    fun randomSummaryCircle(): String = summaryCircles.random()
    fun randomRewriteRelay(): String = rewriteRelays.random()
    fun randomPromptSprint(): String = promptSprints.random()
    fun randomSuggestCircle(): String = suggestCircles.random()
    fun randomVoiceRace(): String = voiceRaces.random()
    fun randomReplySprint(): String = replySprints.random()
    fun randomPixelQuest(): String = pixelQuests.random()
    fun randomAssistCircle(): String = assistCircles.random()
    fun randomDecisionDash(): String = decisionDashes.random()
    fun randomDocHunt(): String = docHunts.random()
    fun randomMeaningRace(): String = meaningRaces.random()
    fun randomInsightSprint(): String = insightSprints.random()
    fun randomGifRelay(): String = gifRelays.random()
    fun randomMarkHunt(): String = markHunts.random()
    fun randomLeakSprint(): String = leakSprints.random()
    fun randomVoiceRing(): String = voiceRings.random()
    fun randomVideoStage(): String = videoStages.random()
    fun randomRingDash(): String = ringDashes.random()
    fun randomWallPick(): String = wallPicks.random()
    fun randomFontRace(): String = fontRaces.random()
    fun randomThemeSprint(): String = themeSprints.random()
    fun randomUnreadRush(): String = unreadRushes.random()
    fun randomRingChoir(): String = ringChoirs.random()
    fun randomAlertSprint(): String = alertSprints.random()
    fun randomSoundWave(): String = soundWaves.random()
    fun randomPreviewMask(): String = previewMasks.random()
    fun randomBeepDash(): String = beepDashes.random()
    fun randomPushRace(): String = pushRaces.random()
    fun randomRemindCircle(): String = remindCircles.random()
    fun randomWakeSprint(): String = wakeSprints.random()
    fun randomQuietHour(): String = quietHours.random()
    fun randomOfflineHint(): String = offlineHints.random()
    fun randomFallbackDash(): String = fallbackDashes.random()
    fun randomClickBeat(): String = clickBeats.random()
    fun randomBuzzRelay(): String = buzzRelays.random()
    fun randomFeelSprint(): String = feelSprints.random()
    fun randomSlideRace(): String = slideRaces.random()
    fun randomFadeCircle(): String = fadeCircles.random()
    fun randomSpringDash(): String = springDashes.random()
    fun randomSnapGuard(): String = snapGuards.random()
    fun randomRecentsHide(): String = recentsHides.random()
    fun randomShieldSprint(): String = shieldSprints.random()
    fun randomCopyLock(): String = copyLocks.random()
    fun randomExportSeal(): String = exportSeals.random()
    fun randomLeakWall(): String = leakWalls.random()
    fun randomForwardSeal(): String = forwardSeals.random()
    fun randomChatExportLock(): String = chatExportLocks.random()
    fun randomVaultFence(): String = vaultFences.random()
    fun randomSealSprint(): String = sealSprints.random()
    fun randomPqxdhDash(): String = pqxdhDashes.random()
    fun randomCertRelay(): String = certRelays.random()
    fun randomMarkSprint(): String = markSprints.random()
    fun randomFadeTimer(): String = fadeTimers.random()
    fun randomStampRelay(): String = stampRelays.random()
    fun randomLinkLock(): String = linkLocks.random()
    fun randomPreviewMute(): String = previewMutes.random()
    fun randomUrlFence(): String = urlFences.random()
    fun randomNotifMask(): String = notifMasks.random()
    fun randomListBlur(): String = listBlurs.random()
    fun randomTraySeal(): String = traySeals.random()
    fun randomReactLock(): String = reactLocks.random()
    fun randomStarSeal(): String = starSeals.random()
    fun randomMetaFence(): String = metaFences.random()
    fun randomTypingSeal(): String = typingSeals.random()
    fun randomReadSeal(): String = readSeals.random()
    fun randomPresenceSeal(): String = presenceSeals.random()
    fun randomLastSeenSeal(): String = lastSeenSeals.random()

    fun formatPhotoRace(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${PHOTO_RACE_PREFIX}${esc(m)}|${hostLabel} photo race"
    }
    fun parsePhotoRace(content: String): String? {
        if (!content.startsWith(PHOTO_RACE_PREFIX)) return null
        return unesc(content.removePrefix(PHOTO_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatClipDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${CLIP_DASH_PREFIX}${esc(m)}|${hostLabel} clip dash"
    }
    fun parseClipDash(content: String): String? {
        if (!content.startsWith(CLIP_DASH_PREFIX)) return null
        return unesc(content.removePrefix(CLIP_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatFrameHunt(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${FRAME_HUNT_PREFIX}${esc(m)}|${hostLabel} frame hunt"
    }
    fun parseFrameHunt(content: String): String? {
        if (!content.startsWith(FRAME_HUNT_PREFIX)) return null
        return unesc(content.removePrefix(FRAME_HUNT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatSummaryCircle(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${SUMMARY_CIRCLE_PREFIX}${esc(m)}|${hostLabel} summary circle"
    }
    fun parseSummaryCircle(content: String): String? {
        if (!content.startsWith(SUMMARY_CIRCLE_PREFIX)) return null
        return unesc(content.removePrefix(SUMMARY_CIRCLE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatRewriteRelay(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${REWRITE_RELAY_PREFIX}${esc(m)}|${hostLabel} rewrite relay"
    }
    fun parseRewriteRelay(content: String): String? {
        if (!content.startsWith(REWRITE_RELAY_PREFIX)) return null
        return unesc(content.removePrefix(REWRITE_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatPromptSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${PROMPT_SPRINT_PREFIX}${esc(m)}|${hostLabel} prompt sprint"
    }
    fun parsePromptSprint(content: String): String? {
        if (!content.startsWith(PROMPT_SPRINT_PREFIX)) return null
        return unesc(content.removePrefix(PROMPT_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }


    fun formatSuggestCircle(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${SUGGEST_CIRCLE_PREFIX}${esc(m)}|${hostLabel} suggest circle"
    }
    fun parseSuggestCircle(content: String): String? {
        if (!content.startsWith(SUGGEST_CIRCLE_PREFIX)) return null
        return unesc(content.removePrefix(SUGGEST_CIRCLE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatVoiceRace(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${VOICE_RACE_PREFIX}${esc(m)}|${hostLabel} voice race"
    }
    fun parseVoiceRace(content: String): String? {
        if (!content.startsWith(VOICE_RACE_PREFIX)) return null
        return unesc(content.removePrefix(VOICE_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatReplySprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${REPLY_SPRINT_PREFIX}${esc(m)}|${hostLabel} reply sprint"
    }
    fun parseReplySprint(content: String): String? {
        if (!content.startsWith(REPLY_SPRINT_PREFIX)) return null
        return unesc(content.removePrefix(REPLY_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatPixelQuest(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${PIXEL_QUEST_PREFIX}${esc(m)}|${hostLabel} pixel quest"
    }
    fun parsePixelQuest(content: String): String? {
        if (!content.startsWith(PIXEL_QUEST_PREFIX)) return null
        return unesc(content.removePrefix(PIXEL_QUEST_PREFIX).substringBefore('|')).ifBlank { null }
    }
fun formatAssistCircle(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${ASSIST_CIRCLE_PREFIX}${esc(m)}|${hostLabel} assist circle"
    }
    fun parseAssistCircle(content: String): String? {
        if (!content.startsWith(ASSIST_CIRCLE_PREFIX)) return null
        return unesc(content.removePrefix(ASSIST_CIRCLE_PREFIX).substringBefore('|')).ifBlank { null }
    }
fun formatDecisionDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${DECISION_DASH_PREFIX}${esc(m)}|${hostLabel} decision dash"
    }
    fun parseDecisionDash(content: String): String? {
        if (!content.startsWith(DECISION_DASH_PREFIX)) return null
        return unesc(content.removePrefix(DECISION_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatDocHunt(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${DOC_HUNT_PREFIX}${esc(m)}|${hostLabel} doc hunt"
    }
    fun parseDocHunt(content: String): String? {
        if (!content.startsWith(DOC_HUNT_PREFIX)) return null
        return unesc(content.removePrefix(DOC_HUNT_PREFIX).substringBefore('|')).ifBlank { null }
    }
fun formatMeaningRace(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${MEANING_RACE_PREFIX}${esc(m)}|${hostLabel} meaning race"
    }
    fun parseMeaningRace(content: String): String? {
        if (!content.startsWith(MEANING_RACE_PREFIX)) return null
        return unesc(content.removePrefix(MEANING_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }
fun formatInsightSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${INSIGHT_SPRINT_PREFIX}${esc(m)}|${hostLabel} insight sprint"
    }
    fun parseInsightSprint(content: String): String? {
        if (!content.startsWith(INSIGHT_SPRINT_PREFIX)) return null
        return unesc(content.removePrefix(INSIGHT_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatGifRelay(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${GIF_RELAY_PREFIX}${esc(m)}|${hostLabel} gif relay"
    }
    fun parseGifRelay(content: String): String? {
        if (!content.startsWith(GIF_RELAY_PREFIX)) return null
        return unesc(content.removePrefix(GIF_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatMarkHunt(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${MARK_HUNT_PREFIX}${esc(m)}|${hostLabel} mark hunt"
    }
    fun parseMarkHunt(content: String): String? {
        if (!content.startsWith(MARK_HUNT_PREFIX)) return null
        return unesc(content.removePrefix(MARK_HUNT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatLeakSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${LEAK_SPRINT_PREFIX}${esc(m)}|${hostLabel} leak sprint"
    }
    fun parseLeakSprint(content: String): String? {
        if (!content.startsWith(LEAK_SPRINT_PREFIX)) return null
        return unesc(content.removePrefix(LEAK_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatVoiceRing(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${VOICE_RING_PREFIX}${esc(m)}|${hostLabel} voice ring"
    }
    fun parseVoiceRing(content: String): String? {
        if (!content.startsWith(VOICE_RING_PREFIX)) return null
        return unesc(content.removePrefix(VOICE_RING_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatVideoStage(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${VIDEO_STAGE_PREFIX}${esc(m)}|${hostLabel} video stage"
    }
    fun parseVideoStage(content: String): String? {
        if (!content.startsWith(VIDEO_STAGE_PREFIX)) return null
        return unesc(content.removePrefix(VIDEO_STAGE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatRingDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${RING_DASH_PREFIX}${esc(m)}|${hostLabel} ring dash"
    }
    fun parseRingDash(content: String): String? {
        if (!content.startsWith(RING_DASH_PREFIX)) return null
        return unesc(content.removePrefix(RING_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatWallPick(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${WALL_PICK_PREFIX}${esc(m)}|${hostLabel} wall pick"
    }
    fun parseWallPick(content: String): String? {
        if (!content.startsWith(WALL_PICK_PREFIX)) return null
        return unesc(content.removePrefix(WALL_PICK_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatFontRace(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${FONT_RACE_PREFIX}${esc(m)}|${hostLabel} font race"
    }
    fun parseFontRace(content: String): String? {
        if (!content.startsWith(FONT_RACE_PREFIX)) return null
        return unesc(content.removePrefix(FONT_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatThemeSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${THEME_SPRINT_PREFIX}${esc(m)}|${hostLabel} theme sprint"
    }
    fun parseThemeSprint(content: String): String? {
        if (!content.startsWith(THEME_SPRINT_PREFIX)) return null
        return unesc(content.removePrefix(THEME_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatUnreadRush(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${UNREAD_RUSH_PREFIX}${esc(m)}|${hostLabel} unread rush"
    }
    fun parseUnreadRush(content: String): String? {
        if (!content.startsWith(UNREAD_RUSH_PREFIX)) return null
        return unesc(content.removePrefix(UNREAD_RUSH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatRingChoir(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${RING_CHOIR_PREFIX}${esc(m)}|${hostLabel} ring choir"
    }
    fun parseRingChoir(content: String): String? {
        if (!content.startsWith(RING_CHOIR_PREFIX)) return null
        return unesc(content.removePrefix(RING_CHOIR_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatAlertSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${ALERT_SPRINT_PREFIX}${esc(m)}|${hostLabel} alert sprint"
    }
    fun parseAlertSprint(content: String): String? {
        if (!content.startsWith(ALERT_SPRINT_PREFIX)) return null
        return unesc(content.removePrefix(ALERT_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatSoundWave(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${SOUND_WAVE_PREFIX}${esc(m)}|${hostLabel} sound wave"
    }
    fun parseSoundWave(content: String): String? {
        if (!content.startsWith(SOUND_WAVE_PREFIX)) return null
        return unesc(content.removePrefix(SOUND_WAVE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatPreviewMask(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${PREVIEW_MASK_PREFIX}${esc(m)}|${hostLabel} preview mask"
    }
    fun parsePreviewMask(content: String): String? {
        if (!content.startsWith(PREVIEW_MASK_PREFIX)) return null
        return unesc(content.removePrefix(PREVIEW_MASK_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatBeepDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${BEEP_DASH_PREFIX}${esc(m)}|${hostLabel} beep dash"
    }
    fun parseBeepDash(content: String): String? {
        if (!content.startsWith(BEEP_DASH_PREFIX)) return null
        return unesc(content.removePrefix(BEEP_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatPushRace(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${PUSH_RACE_PREFIX}${esc(m)}|${hostLabel} push race"
    }
    fun parsePushRace(content: String): String? {
        if (!content.startsWith(PUSH_RACE_PREFIX)) return null
        return unesc(content.removePrefix(PUSH_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatRemindCircle(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${REMIND_CIRCLE_PREFIX}${esc(m)}|${hostLabel} remind circle"
    }
    fun parseRemindCircle(content: String): String? {
        if (!content.startsWith(REMIND_CIRCLE_PREFIX)) return null
        return unesc(content.removePrefix(REMIND_CIRCLE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatWakeSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${WAKE_SPRINT_PREFIX}${esc(m)}|${hostLabel} wake sprint"
    }
    fun parseWakeSprint(content: String): String? {
        if (!content.startsWith(WAKE_SPRINT_PREFIX)) return null
        return unesc(content.removePrefix(WAKE_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatQuietHour(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${QUIET_HOUR_PREFIX}${esc(m)}|${hostLabel} quiet hour"
    }
    fun parseQuietHour(content: String): String? {
        if (!content.startsWith(QUIET_HOUR_PREFIX)) return null
        return unesc(content.removePrefix(QUIET_HOUR_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatOfflineHint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${OFFLINE_HINT_PREFIX}${esc(m)}|${hostLabel} offline hint"
    }
    fun parseOfflineHint(content: String): String? {
        if (!content.startsWith(OFFLINE_HINT_PREFIX)) return null
        return unesc(content.removePrefix(OFFLINE_HINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatFallbackDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${FALLBACK_DASH_PREFIX}${esc(m)}|${hostLabel} fallback dash"
    }
    fun parseFallbackDash(content: String): String? {
        if (!content.startsWith(FALLBACK_DASH_PREFIX)) return null
        return unesc(content.removePrefix(FALLBACK_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatClickBeat(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${CLICK_BEAT_PREFIX}${esc(m)}|${hostLabel} click beat"
    }
    fun parseClickBeat(content: String): String? {
        if (!content.startsWith(CLICK_BEAT_PREFIX)) return null
        return unesc(content.removePrefix(CLICK_BEAT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatBuzzRelay(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${BUZZ_RELAY_PREFIX}${esc(m)}|${hostLabel} buzz relay"
    }
    fun parseBuzzRelay(content: String): String? {
        if (!content.startsWith(BUZZ_RELAY_PREFIX)) return null
        return unesc(content.removePrefix(BUZZ_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatFeelSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${FEEL_SPRINT_PREFIX}${esc(m)}|${hostLabel} feel sprint"
    }
    fun parseFeelSprint(content: String): String? {
        if (!content.startsWith(FEEL_SPRINT_PREFIX)) return null
        return unesc(content.removePrefix(FEEL_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatSlideRace(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${SLIDE_RACE_PREFIX}${esc(m)}|${hostLabel} slide race"
    }
    fun parseSlideRace(content: String): String? {
        if (!content.startsWith(SLIDE_RACE_PREFIX)) return null
        return unesc(content.removePrefix(SLIDE_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatFadeCircle(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${FADE_CIRCLE_PREFIX}${esc(m)}|${hostLabel} fade circle"
    }
    fun parseFadeCircle(content: String): String? {
        if (!content.startsWith(FADE_CIRCLE_PREFIX)) return null
        return unesc(content.removePrefix(FADE_CIRCLE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatSpringDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${SPRING_DASH_PREFIX}${esc(m)}|${hostLabel} spring dash"
    }
    fun parseSpringDash(content: String): String? {
        if (!content.startsWith(SPRING_DASH_PREFIX)) return null
        return unesc(content.removePrefix(SPRING_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatSnapGuard(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${SNAP_GUARD_PREFIX}${esc(m)}|${hostLabel} snap guard"
    }
    fun parseSnapGuard(content: String): String? {
        if (!content.startsWith(SNAP_GUARD_PREFIX)) return null
        return unesc(content.removePrefix(SNAP_GUARD_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatRecentsHide(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${RECENTS_HIDE_PREFIX}${esc(m)}|${hostLabel} recents hide"
    }
    fun parseRecentsHide(content: String): String? {
        if (!content.startsWith(RECENTS_HIDE_PREFIX)) return null
        return unesc(content.removePrefix(RECENTS_HIDE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatShieldSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${SHIELD_SPRINT_PREFIX}${esc(m)}|${hostLabel} shield sprint"
    }
    fun parseShieldSprint(content: String): String? {
        if (!content.startsWith(SHIELD_SPRINT_PREFIX)) return null
        return unesc(content.removePrefix(SHIELD_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatCopyLock(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${COPY_LOCK_PREFIX}${esc(m)}|${hostLabel} copy lock"
    }
    fun parseCopyLock(content: String): String? {
        if (!content.startsWith(COPY_LOCK_PREFIX)) return null
        return unesc(content.removePrefix(COPY_LOCK_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatExportSeal(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${EXPORT_SEAL_PREFIX}${esc(m)}|${hostLabel} export seal"
    }
    fun parseExportSeal(content: String): String? {
        if (!content.startsWith(EXPORT_SEAL_PREFIX)) return null
        return unesc(content.removePrefix(EXPORT_SEAL_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatLeakWall(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${LEAK_WALL_PREFIX}${esc(m)}|${hostLabel} leak wall"
    }
    fun parseLeakWall(content: String): String? {
        if (!content.startsWith(LEAK_WALL_PREFIX)) return null
        return unesc(content.removePrefix(LEAK_WALL_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatForwardSeal(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${FORWARD_SEAL_PREFIX}${esc(m)}|${hostLabel} forward seal"
    }
    fun parseForwardSeal(content: String): String? {
        if (!content.startsWith(FORWARD_SEAL_PREFIX)) return null
        return unesc(content.removePrefix(FORWARD_SEAL_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatChatExportLock(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${CHAT_EXPORT_LOCK_PREFIX}${esc(m)}|${hostLabel} chat export lock"
    }
    fun parseChatExportLock(content: String): String? {
        if (!content.startsWith(CHAT_EXPORT_LOCK_PREFIX)) return null
        return unesc(content.removePrefix(CHAT_EXPORT_LOCK_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatVaultFence(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${VAULT_FENCE_PREFIX}${esc(m)}|${hostLabel} vault fence"
    }
    fun parseVaultFence(content: String): String? {
        if (!content.startsWith(VAULT_FENCE_PREFIX)) return null
        return unesc(content.removePrefix(VAULT_FENCE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatSealSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${SEAL_SPRINT_PREFIX}${esc(m)}|${hostLabel} seal sprint"
    }
    fun parseSealSprint(content: String): String? {
        if (!content.startsWith(SEAL_SPRINT_PREFIX)) return null
        return unesc(content.removePrefix(SEAL_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatPqxdhDash(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${PQXDH_DASH_PREFIX}${esc(m)}|${hostLabel} pqxdh dash"
    }
    fun parsePqxdhDash(content: String): String? {
        if (!content.startsWith(PQXDH_DASH_PREFIX)) return null
        return unesc(content.removePrefix(PQXDH_DASH_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatCertRelay(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${CERT_RELAY_PREFIX}${esc(m)}|${hostLabel} cert relay"
    }
    fun parseCertRelay(content: String): String? {
        if (!content.startsWith(CERT_RELAY_PREFIX)) return null
        return unesc(content.removePrefix(CERT_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatMarkSprint(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${MARK_SPRINT_PREFIX}${esc(m)}|${hostLabel} mark sprint"
    }
    fun parseMarkSprint(content: String): String? {
        if (!content.startsWith(MARK_SPRINT_PREFIX)) return null
        return unesc(content.removePrefix(MARK_SPRINT_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatFadeTimer(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${FADE_TIMER_PREFIX}${esc(m)}|${hostLabel} fade timer"
    }
    fun parseFadeTimer(content: String): String? {
        if (!content.startsWith(FADE_TIMER_PREFIX)) return null
        return unesc(content.removePrefix(FADE_TIMER_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatStampRelay(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${STAMP_RELAY_PREFIX}${esc(m)}|${hostLabel} stamp relay"
    }
    fun parseStampRelay(content: String): String? {
        if (!content.startsWith(STAMP_RELAY_PREFIX)) return null
        return unesc(content.removePrefix(STAMP_RELAY_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatLinkLock(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${LINK_LOCK_PREFIX}${esc(m)}|${hostLabel} link lock"
    }
    fun parseLinkLock(content: String): String? {
        if (!content.startsWith(LINK_LOCK_PREFIX)) return null
        return unesc(content.removePrefix(LINK_LOCK_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatPreviewMute(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${PREVIEW_MUTE_PREFIX}${esc(m)}|${hostLabel} preview mute"
    }
    fun parsePreviewMute(content: String): String? {
        if (!content.startsWith(PREVIEW_MUTE_PREFIX)) return null
        return unesc(content.removePrefix(PREVIEW_MUTE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatUrlFence(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${URL_FENCE_PREFIX}${esc(m)}|${hostLabel} url fence"
    }
    fun parseUrlFence(content: String): String? {
        if (!content.startsWith(URL_FENCE_PREFIX)) return null
        return unesc(content.removePrefix(URL_FENCE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatNotifMask(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${NOTIF_MASK_PREFIX}${esc(m)}|${hostLabel} notif mask"
    }
    fun parseNotifMask(content: String): String? {
        if (!content.startsWith(NOTIF_MASK_PREFIX)) return null
        return unesc(content.removePrefix(NOTIF_MASK_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatListBlur(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${LIST_BLUR_PREFIX}${esc(m)}|${hostLabel} list blur"
    }
    fun parseListBlur(content: String): String? {
        if (!content.startsWith(LIST_BLUR_PREFIX)) return null
        return unesc(content.removePrefix(LIST_BLUR_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatTraySeal(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${TRAY_SEAL_PREFIX}${esc(m)}|${hostLabel} tray seal"
    }
    fun parseTraySeal(content: String): String? {
        if (!content.startsWith(TRAY_SEAL_PREFIX)) return null
        return unesc(content.removePrefix(TRAY_SEAL_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatReactLock(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${REACT_LOCK_PREFIX}${esc(m)}|${hostLabel} react lock"
    }
    fun parseReactLock(content: String): String? {
        if (!content.startsWith(REACT_LOCK_PREFIX)) return null
        return unesc(content.removePrefix(REACT_LOCK_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatStarSeal(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${STAR_SEAL_PREFIX}${esc(m)}|${hostLabel} star seal"
    }
    fun parseStarSeal(content: String): String? {
        if (!content.startsWith(STAR_SEAL_PREFIX)) return null
        return unesc(content.removePrefix(STAR_SEAL_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatMetaFence(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${META_FENCE_PREFIX}${esc(m)}|${hostLabel} meta fence"
    }
    fun parseMetaFence(content: String): String? {
        if (!content.startsWith(META_FENCE_PREFIX)) return null
        return unesc(content.removePrefix(META_FENCE_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatTypingSeal(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${TYPING_SEAL_PREFIX}${esc(m)}|${hostLabel} typing seal"
    }
    fun parseTypingSeal(content: String): String? {
        if (!content.startsWith(TYPING_SEAL_PREFIX)) return null
        return unesc(content.removePrefix(TYPING_SEAL_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatReadSeal(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${READ_SEAL_PREFIX}${esc(m)}|${hostLabel} read seal"
    }
    fun parseReadSeal(content: String): String? {
        if (!content.startsWith(READ_SEAL_PREFIX)) return null
        return unesc(content.removePrefix(READ_SEAL_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatPresenceSeal(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${PRESENCE_SEAL_PREFIX}${esc(m)}|${hostLabel} presence seal"
    }
    fun parsePresenceSeal(content: String): String? {
        if (!content.startsWith(PRESENCE_SEAL_PREFIX)) return null
        return unesc(content.removePrefix(PRESENCE_SEAL_PREFIX).substringBefore('|')).ifBlank { null }
    }
    fun formatLastSeenSeal(mode: String, hostLabel: String): String {
        val m = mode.trim().take(40)
        return "${LASTSEEN_SEAL_PREFIX}${esc(m)}|${hostLabel} last seen seal"
    }
    fun parseLastSeenSeal(content: String): String? {
        if (!content.startsWith(LASTSEEN_SEAL_PREFIX)) return null
        return unesc(content.removePrefix(LASTSEEN_SEAL_PREFIX).substringBefore('|')).ifBlank { null }
    }
}
