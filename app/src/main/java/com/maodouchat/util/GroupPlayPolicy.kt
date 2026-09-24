package com.maodouchat.util

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Group play helpers (polls, dice, check-in) — pure/local formatting.
 * Poll persistence is server-backed; dice is ephemeral system text with optional E2EE payload.
 */
object GroupPlayPolicy {
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
        val head = GroupPlayFieldEscape.unesc(body.substringBefore('|'))
        val parts = head.split(':')
        if (parts.size < 2) return null
        val sides = parts[0].toIntOrNull() ?: return null
        val value = parts[1].toIntOrNull() ?: return null
        return sides to value
    }

    fun formatCheckIn(dayStreak: Int, userLabel: String): String =
        com.maodouchat.group.play.GroupCheckinPolicy.formatCheckIn(dayStreak, userLabel)

    fun buildPollPayload(
        pollId: String,
        question: String,
        options: List<String>,
        multi: Boolean,
        anonymous: Boolean
    ): String = com.maodouchat.group.play.GroupPollPolicy.buildPollPayload(pollId, question, options, multi, anonymous)

    fun parsePoll(content: String): JSONObject? =
        com.maodouchat.group.play.GroupPollPolicy.parsePoll(content)

    fun formatLuckyDraw(pickerName: String, targetName: String): String {
        return "LUCKY:${GroupPlayFieldEscape.esc(pickerName)}|$targetName"
    }

    fun parseLuckyDraw(content: String): Pair<String, String>? {
        if (!content.startsWith("LUCKY:")) return null
        val body = content.removePrefix("LUCKY:")
        val a = GroupPlayFieldEscape.unesc(body.substringBefore('|'))
        val b = body.substringAfter('|', "")
        if (a.isBlank() || b.isBlank()) return null
        return a to b
    }

    const val RPS_PREFIX = "RPS:"
    const val TRUTH_PREFIX = "TRUTH:"
    const val ANON_PREFIX = "ANON:"

    fun rollRps(): String = GroupPlayClassicPolicy.rollRps()

    fun formatRps(choice: String, userLabel: String): String = GroupPlayClassicPolicy.formatRps(choice, userLabel)

    fun parseRps(content: String): String? = GroupPlayClassicPolicy.parseRps(content)

    const val BOMB_PREFIX = "BOMB:"
    const val WORD_PREFIX = "WORD:"

    fun rollNumberBomb(max: Int = 100): Pair<Int, Int> = GroupPlayClassicPolicy.rollNumberBomb(max)

    fun formatNumberBomb(secret: Int, max: Int, hostLabel: String): String = GroupPlayClassicPolicy.formatNumberBomb(secret, max, hostLabel)

    fun parseNumberBomb(content: String): Triple<Int, Int, String>? = GroupPlayClassicPolicy.parseNumberBomb(content)

    fun randomWordSeed(): String = GroupPlayClassicPolicy.randomWordSeed()

    fun formatWordChain(seed: String, userLabel: String): String = GroupPlayClassicPolicy.formatWordChain(seed, userLabel)

    const val RACE_PREFIX = "RACE:"

    fun randomRaceToken(): String = GroupPlayClassicPolicy.randomRaceToken()

    fun formatReactionRace(token: String, hostLabel: String): String = GroupPlayClassicPolicy.formatReactionRace(token, hostLabel)

    fun parseReactionRace(content: String): Pair<String, String>? = GroupPlayClassicPolicy.parseReactionRace(content)

    const val WOULD_PREFIX = "WOULD:"
    const val EMOJI_RAIN_PREFIX = "EMOJI_RAIN:"

    fun formatWouldYouRather(a: String, b: String, hostLabel: String): String = GroupPlayClassicPolicy.formatWouldYouRather(a, b, hostLabel)

    fun parseWouldYouRather(content: String): Triple<String, String, String>? = GroupPlayClassicPolicy.parseWouldYouRather(content)

    fun formatEmojiRain(hostLabel: String, emoji: String = rainEmojis.random()): String = GroupPlayClassicPolicy.formatEmojiRain(hostLabel, emoji)

    fun parseEmojiRain(content: String): Pair<String, String>? = GroupPlayClassicPolicy.parseEmojiRain(content)

    const val TRUTHS_PREFIX = "TRUTHS:"
    const val QUIZ_PREFIX = "QUIZ:"

    fun formatTwoTruthsOneLie(t1: String, t2: String, lie: String, hostLabel: String): String = GroupPlayClassicPolicy.formatTwoTruthsOneLie(t1, t2, lie, hostLabel)

    fun parseTwoTruthsOneLie(content: String): List<String>? = GroupPlayClassicPolicy.parseTwoTruthsOneLie(content)

    fun formatQuiz(question: String, answer: String, options: List<String>, hostLabel: String): String = GroupPlayClassicPolicy.formatQuiz(question, answer, options, hostLabel)

    fun parseQuiz(content: String): Triple<String, String, List<String>>? = GroupPlayClassicPolicy.parseQuiz(content)

    const val SPIN_PREFIX = "SPIN:"
    const val STORY_PREFIX = "STORY:"
    const val COUNTDOWN_PREFIX = "COUNTDOWN:"

    fun spinWheel(): String = GroupPlayClassicPolicy.spinWheel()

    fun formatSpin(result: String, hostLabel: String): String = GroupPlayClassicPolicy.formatSpin(result, hostLabel)

    fun parseSpin(content: String): String? = GroupPlayClassicPolicy.parseSpin(content)

    fun formatStory(seed: String, hostLabel: String): String = GroupPlayClassicPolicy.formatStory(seed, hostLabel)

    fun parseStory(content: String): String? = GroupPlayClassicPolicy.parseStory(content)

    fun formatCountdown(seconds: Int, hostLabel: String): String = GroupPlayClassicPolicy.formatCountdown(seconds, hostLabel)

    fun parseCountdown(content: String): Int? = GroupPlayClassicPolicy.parseCountdown(content)

    const val BINGO_PREFIX = "BINGO:"
    const val LOTTERY_PREFIX = "LOTTERY:"
    const val HOTSEAT_PREFIX = "HOTSEAT:"

    fun formatBingo(board: List<String>, hostLabel: String): String = GroupPlayClassicPolicy.formatBingo(board, hostLabel)

    fun parseBingo(content: String): List<String>? = GroupPlayClassicPolicy.parseBingo(content)

    fun formatLottery(pool: List<String>, winner: String, hostLabel: String): String = GroupPlayClassicPolicy.formatLottery(pool, winner, hostLabel)

    fun parseLottery(content: String): Pair<String, List<String>>? = GroupPlayClassicPolicy.parseLottery(content)

    fun formatHotSeat(target: String, hostLabel: String): String = GroupPlayClassicPolicy.formatHotSeat(target, hostLabel)

    fun parseHotSeat(content: String): String? = GroupPlayClassicPolicy.parseHotSeat(content)

    const val COINFLIP_PREFIX = "COINFLIP:"
    const val REDPACKET_PREFIX = "REDPACKET:"

    fun formatCoinFlip(side: String, hostLabel: String): String = GroupPlayClassicPolicy.formatCoinFlip(side, hostLabel)

    fun parseCoinFlip(content: String): String? = GroupPlayClassicPolicy.parseCoinFlip(content)

    fun formatRedPacketJoke(amountLabel: String, hostLabel: String): String = GroupPlayClassicPolicy.formatRedPacketJoke(amountLabel, hostLabel)

    fun parseRedPacketJoke(content: String): String? = GroupPlayClassicPolicy.parseRedPacketJoke(content)

    const val CHARADES_PREFIX = "CHARADES:"
    const val NUMBERGUESS_PREFIX = "NUMGUESS:"

    fun formatCharades(prompt: String, hostLabel: String): String = GroupPlayClassicPolicy.formatCharades(prompt, hostLabel)

    fun parseCharades(content: String): String? = GroupPlayClassicPolicy.parseCharades(content)

    fun formatNumberGuess(secret: Int, max: Int, hostLabel: String): String = GroupPlayClassicPolicy.formatNumberGuess(secret, max, hostLabel)

    fun parseNumberGuess(content: String): Pair<Int, Int>? = GroupPlayClassicPolicy.parseNumberGuess(content)

    const val IMPOSTOR_PREFIX = "IMPOSTOR:"
    const val RIDDLE_PREFIX = "RIDDLE:"
    const val EMOJI_STORY_PREFIX = "EMOJISTORY:"

    fun formatRiddle(q: String, a: String, hostLabel: String): String = GroupPlayClassicPolicy.formatRiddle(q, a, hostLabel)

    fun parseRiddle(content: String): Pair<String, String>? = GroupPlayClassicPolicy.parseRiddle(content)

    fun formatImpostor(word: String, hostLabel: String): String = GroupPlayClassicPolicy.formatImpostor(word, hostLabel)

    fun parseImpostor(content: String): String? = GroupPlayClassicPolicy.parseImpostor(content)

    fun formatEmojiStory(seed: String, hostLabel: String): String = GroupPlayClassicPolicy.formatEmojiStory(seed, hostLabel)

    fun parseEmojiStory(content: String): String? = GroupPlayClassicPolicy.parseEmojiStory(content)

    const val SIMON_PREFIX = "SIMON:"
    const val HOTORNOT_PREFIX = "HOTORNOT:"
    const val ALPHABET_PREFIX = "ALPHABET:"

    fun formatSimon(seq: String, hostLabel: String): String = GroupPlayClassicPolicy.formatSimon(seq, hostLabel)

    fun parseSimon(content: String): String? = GroupPlayClassicPolicy.parseSimon(content)

    fun formatHotOrNot(topic: String, hostLabel: String): String = GroupPlayClassicPolicy.formatHotOrNot(topic, hostLabel)

    fun parseHotOrNot(content: String): String? = GroupPlayClassicPolicy.parseHotOrNot(content)

    fun formatAlphabet(letter: String, hostLabel: String): String = GroupPlayClassicPolicy.formatAlphabet(letter, hostLabel)

    fun parseAlphabet(content: String): String? = GroupPlayClassicPolicy.parseAlphabet(content)

    const val TRIVIA_PREFIX = "TRIVIA:"
    const val SPEED_PREFIX = "SPEED:"

    fun formatTrivia(q: String, a: String, hostLabel: String): String = GroupPlayClassicPolicy.formatTrivia(q, a, hostLabel)

    fun parseTrivia(content: String): Pair<String, String>? = GroupPlayClassicPolicy.parseTrivia(content)

    fun formatSpeedChallenge(sec: Int, hostLabel: String): String = GroupPlayClassicPolicy.formatSpeedChallenge(sec, hostLabel)

    fun parseSpeedChallenge(content: String): Int? = GroupPlayClassicPolicy.parseSpeedChallenge(content)

    const val TRUTH_OR_DARE_PREFIX = "TRUTHDARE:"
    const val NEVER_HAVE_PREFIX = "NEVERHAVE:"
    const val MEMORY_MATCH_PREFIX = "MEMORY:"
    const val DRAW_PROMPT_PREFIX = "DRAWPROMPT:"

    fun randomMemoryBoard(): String = GroupPlayClassicPolicy.randomMemoryBoard()

    fun formatTruthOrDare(mode: String, prompt: String, hostLabel: String): String = GroupPlayClassicPolicy.formatTruthOrDare(mode, prompt, hostLabel)

    fun parseTruthOrDare(content: String): Pair<String, String>? = GroupPlayClassicPolicy.parseTruthOrDare(content)

    fun formatNeverHaveIEver(prompt: String, hostLabel: String): String = GroupPlayClassicPolicy.formatNeverHaveIEver(prompt, hostLabel)

    fun parseNeverHaveIEver(content: String): String? = GroupPlayClassicPolicy.parseNeverHaveIEver(content)

    fun formatMemoryMatch(board: String, hostLabel: String): String = GroupPlayClassicPolicy.formatMemoryMatch(board, hostLabel)

    fun parseMemoryMatch(content: String): String? = GroupPlayClassicPolicy.parseMemoryMatch(content)

    fun formatDrawPrompt(prompt: String, hostLabel: String): String = GroupPlayClassicPolicy.formatDrawPrompt(prompt, hostLabel)

    fun parseDrawPrompt(content: String): String? = GroupPlayClassicPolicy.parseDrawPrompt(content)

    const val ICEBREAKER_PREFIX = "ICEBREAKER:"
    const val EMOJI_DUEL_PREFIX = "EMOJIDUEL:"
    const val RAPID_FIRE_PREFIX = "RAPIDFIRE:"

    fun randomEmojiDuel(): String = GroupPlayClassicPolicy.randomEmojiDuel()

    fun formatIcebreaker(prompt: String, hostLabel: String): String = GroupPlayClassicPolicy.formatIcebreaker(prompt, hostLabel)

    fun parseIcebreaker(content: String): String? = GroupPlayClassicPolicy.parseIcebreaker(content)

    fun formatEmojiDuel(pair: String, hostLabel: String): String = GroupPlayClassicPolicy.formatEmojiDuel(pair, hostLabel)

    fun parseEmojiDuel(content: String): String? = GroupPlayClassicPolicy.parseEmojiDuel(content)

    fun formatRapidFire(topic: String, hostLabel: String): String = GroupPlayClassicPolicy.formatRapidFire(topic, hostLabel)

    fun parseRapidFire(content: String): String? = GroupPlayClassicPolicy.parseRapidFire(content)

    const val SCATTER_PREFIX = "SCATTER:"
    const val MINUTE_TALK_PREFIX = "MINUTETALK:"
    const val CAPTION_THIS_PREFIX = "CAPTION:"

    fun formatScatter(letter: String, category: String, hostLabel: String): String = GroupPlayClassicPolicy.formatScatter(letter, category, hostLabel)

    fun parseScatter(content: String): Pair<String, String>? = GroupPlayClassicPolicy.parseScatter(content)

    fun formatMinuteTalk(topic: String, hostLabel: String): String = GroupPlayClassicPolicy.formatMinuteTalk(topic, hostLabel)

    fun parseMinuteTalk(content: String): String? = GroupPlayClassicPolicy.parseMinuteTalk(content)

    fun formatCaptionThis(seed: String, hostLabel: String): String = GroupPlayClassicPolicy.formatCaptionThis(seed, hostLabel)

    fun parseCaptionThis(content: String): String? = GroupPlayClassicPolicy.parseCaptionThis(content)

    const val STORY_SWAP_PREFIX = "STORYSWAP:"
    const val KARAOKE_PREFIX = "KARAOKE:"
    const val BLIND_Q_PREFIX = "BLINDQ:"

    fun formatStorySwap(opener: String, hostLabel: String): String = GroupPlayClassicPolicy.formatStorySwap(opener, hostLabel)

    fun parseStorySwap(content: String): String? = GroupPlayClassicPolicy.parseStorySwap(content)

    fun formatKaraoke(line: String, hostLabel: String): String = GroupPlayClassicPolicy.formatKaraoke(line, hostLabel)

    fun parseKaraoke(content: String): String? = GroupPlayClassicPolicy.parseKaraoke(content)

    fun formatBlindQ(q: String, hostLabel: String): String = GroupPlayClassicPolicy.formatBlindQ(q, hostLabel)

    fun parseBlindQ(content: String): String? = GroupPlayClassicPolicy.parseBlindQ(content)

    const val FORTUNE_PREFIX = "FORTUNE:"
    const val EMOJI_QUIZ_PREFIX = "EMOJIQUIZ:"
    const val CHAIN_REACT_PREFIX = "CHAINREACT:"

    fun randomChainSeed(): String = GroupPlayClassicPolicy.randomChainSeed()

    fun formatFortune(text: String, hostLabel: String): String = GroupPlayClassicPolicy.formatFortune(text, hostLabel)

    fun parseFortune(content: String): String? = GroupPlayClassicPolicy.parseFortune(content)

    fun formatEmojiQuiz(prompt: String, answer: String, hostLabel: String): String = GroupPlayClassicPolicy.formatEmojiQuiz(prompt, answer, hostLabel)

    fun parseEmojiQuiz(content: String): Pair<String, String>? = GroupPlayClassicPolicy.parseEmojiQuiz(content)

    fun formatChainReact(seed: String, hostLabel: String): String = GroupPlayClassicPolicy.formatChainReact(seed, hostLabel)

    fun parseChainReact(content: String): String? = GroupPlayClassicPolicy.parseChainReact(content)

    const val DEBATE_PREFIX = "DEBATE:"
    const val MIRROR_PREFIX = "MIRROR:"
    const val HIDESEEK_PREFIX = "HIDESEEK:"
    const val TOAST_PREFIX = "TOAST:"

    fun randomHideEmoji(): String = GroupPlayClassicPolicy.randomHideEmoji()

    fun formatDebate(topic: String, hostLabel: String): String = GroupPlayClassicPolicy.formatDebate(topic, hostLabel)

    fun parseDebate(content: String): String? = GroupPlayClassicPolicy.parseDebate(content)

    fun formatMirror(line: String, hostLabel: String): String = GroupPlayClassicPolicy.formatMirror(line, hostLabel)

    fun parseMirror(content: String): String? = GroupPlayClassicPolicy.parseMirror(content)

    fun formatHideSeek(emoji: String, hostLabel: String): String = GroupPlayClassicPolicy.formatHideSeek(emoji, hostLabel)

    fun parseHideSeek(content: String): String? = GroupPlayClassicPolicy.parseHideSeek(content)

    fun formatToast(line: String, hostLabel: String): String = GroupPlayClassicPolicy.formatToast(line, hostLabel)

    fun parseToast(content: String): String? = GroupPlayClassicPolicy.parseToast(content)

    const val HOTPOTATO_PREFIX = "HOTPOTATO:"
    const val WORDHINT_PREFIX = "WORDHINT:"

    fun formatHotPotato(seconds: Int, hostLabel: String): String = GroupPlayClassicPolicy.formatHotPotato(seconds, hostLabel)

    fun parseHotPotato(content: String): Int? = GroupPlayClassicPolicy.parseHotPotato(content)

    fun formatWordHint(hint: String, answer: String, hostLabel: String): String = GroupPlayClassicPolicy.formatWordHint(hint, answer, hostLabel)

    fun parseWordHint(content: String): Pair<String, String>? = GroupPlayClassicPolicy.parseWordHint(content)

    const val SPYFALL_PREFIX = "SPYFALL:"
    const val ACROSTIC_PREFIX = "ACROSTIC:"
    const val EMOJI_TR_PREFIX = "EMOJITR:"

    fun formatSpyfall(location: String, hostLabel: String): String = GroupPlayClassicPolicy.formatSpyfall(location, hostLabel)

    fun parseSpyfall(content: String): String? = GroupPlayClassicPolicy.parseSpyfall(content)

    fun formatAcrostic(seed: String, hostLabel: String): String = GroupPlayClassicPolicy.formatAcrostic(seed, hostLabel)

    fun parseAcrostic(content: String): String? = GroupPlayClassicPolicy.parseAcrostic(content)

    fun formatEmojiTranslate(prompt: String, answer: String, hostLabel: String): String = GroupPlayClassicPolicy.formatEmojiTranslate(prompt, answer, hostLabel)

    fun parseEmojiTranslate(content: String): Pair<String, String>? = GroupPlayClassicPolicy.parseEmojiTranslate(content)

    const val TWENTYQ_PREFIX = "TWENTYQ:"
    const val RHYME_PREFIX = "RHYME:"
    const val ODDONE_PREFIX = "ODDONE:"

    fun formatTwentyQuestions(subject: String, hostLabel: String): String = GroupPlayClassicPolicy.formatTwentyQuestions(subject, hostLabel)

    fun parseTwentyQuestions(content: String): String? = GroupPlayClassicPolicy.parseTwentyQuestions(content)

    fun formatRhyme(seed: String, hostLabel: String): String = GroupPlayClassicPolicy.formatRhyme(seed, hostLabel)

    fun parseRhyme(content: String): String? = GroupPlayClassicPolicy.parseRhyme(content)

    fun formatOddOneOut(options: String, answer: String, hostLabel: String): String = GroupPlayClassicPolicy.formatOddOneOut(options, answer, hostLabel)

    fun parseOddOneOut(content: String): Pair<String, String>? = GroupPlayClassicPolicy.parseOddOneOut(content)

    const val CATEGORIES_PREFIX = "CATEGORIES:"
    const val PASSWORD_PREFIX = "PASSWORD:"
    const val TIMECAPSULE_PREFIX = "TIMECAPSULE:"

    fun formatCategories(cat: String, hostLabel: String): String = GroupPlayClassicPolicy.formatCategories(cat, hostLabel)

    fun parseCategories(content: String): String? = GroupPlayClassicPolicy.parseCategories(content)

    fun formatPasswordGame(hint: String, hostLabel: String): String = GroupPlayClassicPolicy.formatPasswordGame(hint, hostLabel)

    fun parsePasswordGame(content: String): String? = GroupPlayClassicPolicy.parsePasswordGame(content)

    fun formatTimeCapsule(note: String, hostLabel: String): String = GroupPlayClassicPolicy.formatTimeCapsule(note, hostLabel)

    fun parseTimeCapsule(content: String): String? = GroupPlayClassicPolicy.parseTimeCapsule(content)

    const val TABOO_PREFIX = "TABOO:"
    const val LIGHTNING_PREFIX = "LIGHTNING:"
    const val TWO_WORDS_PREFIX = "TWOWORDS:"

    fun formatTaboo(card: String, hostLabel: String): String = GroupPlayClassicPolicy.formatTaboo(card, hostLabel)

    fun parseTaboo(content: String): String? = GroupPlayClassicPolicy.parseTaboo(content)

    fun formatLightning(prompt: String, hostLabel: String): String = GroupPlayClassicPolicy.formatLightning(prompt, hostLabel)

    fun parseLightning(content: String): String? = GroupPlayClassicPolicy.parseLightning(content)

    fun formatTwoWords(seed: String, hostLabel: String): String = GroupPlayClassicPolicy.formatTwoWords(seed, hostLabel)

    fun parseTwoWords(content: String): String? = GroupPlayClassicPolicy.parseTwoWords(content)

    const val WHISPER_PREFIX = "WHISPER:"
    const val COUNTDOWN_RACE_PREFIX = "COUNTRACE:"

    fun formatWhisper(prompt: String, hostLabel: String): String = GroupPlayClassicPolicy.formatWhisper(prompt, hostLabel)

    fun parseWhisper(content: String): String? = GroupPlayClassicPolicy.parseWhisper(content)

    fun formatCountdownRace(seconds: Int, hostLabel: String): String = GroupPlayClassicPolicy.formatCountdownRace(seconds, hostLabel)

    fun parseCountdownRace(content: String): String? = GroupPlayClassicPolicy.parseCountdownRace(content)

    const val EMOJI_MEMORY_PREFIX = "EMOJIMEM:"
    const val GEO_GUESS_PREFIX = "GEOGUESS:"

    fun formatEmojiMemory(board: String, hostLabel: String): String = GroupPlayClassicPolicy.formatEmojiMemory(board, hostLabel)

    fun parseEmojiMemory(content: String): String? = GroupPlayClassicPolicy.parseEmojiMemory(content)

    fun formatGeoGuess(clue: String, hostLabel: String): String = GroupPlayClassicPolicy.formatGeoGuess(clue, hostLabel)

    fun parseGeoGuess(content: String): String? = GroupPlayClassicPolicy.parseGeoGuess(content)

    const val ONE_WORD_PREFIX = "ONEWORD:"
    const val SPEED_MATH_PREFIX = "SPEEDMATH:"
    const val STORY_SEED_PREFIX = "STORYSEED:"

    fun formatOneWord(word: String, hostLabel: String): String = GroupPlayClassicPolicy.formatOneWord(word, hostLabel)

    fun parseOneWord(content: String): String? = GroupPlayClassicPolicy.parseOneWord(content)

    fun formatSpeedMath(q: String, hostLabel: String): String = GroupPlayClassicPolicy.formatSpeedMath(q, hostLabel)

    fun parseSpeedMath(content: String): String? = GroupPlayClassicPolicy.parseSpeedMath(content)

    fun formatStorySeed(seed: String, hostLabel: String): String = GroupPlayClassicPolicy.formatStorySeed(seed, hostLabel)

    fun parseStorySeed(content: String): String? = GroupPlayClassicPolicy.parseStorySeed(content)

    const val WOULD_YOU_PREFIX2 = "WOULD2:"
    const val EMOJI_ONLY_PREFIX = "EMOJIONLY:"
    const val BLIND_DRAW_PREFIX = "BLINDDRAW:"

    fun formatWould2(a: String, b: String, hostLabel: String): String = GroupPlayClassicPolicy.formatWould2(a, b, hostLabel)

    fun parseWould2(content: String): Pair<String, String>? = GroupPlayClassicPolicy.parseWould2(content)

    fun formatEmojiOnly(prompt: String, hostLabel: String): String = GroupPlayClassicPolicy.formatEmojiOnly(prompt, hostLabel)

    fun parseEmojiOnly(content: String): String? = GroupPlayClassicPolicy.parseEmojiOnly(content)

    fun formatBlindDraw(token: String, hostLabel: String): String = GroupPlayClassicPolicy.formatBlindDraw(token, hostLabel)

    fun parseBlindDraw(content: String): String? = GroupPlayClassicPolicy.parseBlindDraw(content)

    const val ALPHABET_RACE_PREFIX = "ALPHARACE:"
    const val SILENT_MOVIE_PREFIX = "SILENTMOVIE:"
    const val COLOR_WORD_PREFIX = "COLORWORD:"

    fun formatAlphabetRace(start: String, hostLabel: String): String = GroupPlayClassicPolicy.formatAlphabetRace(start, hostLabel)

    fun parseAlphabetRace(content: String): String? = GroupPlayClassicPolicy.parseAlphabetRace(content)

    fun formatSilentMovie(prompt: String, hostLabel: String): String = GroupPlayClassicPolicy.formatSilentMovie(prompt, hostLabel)

    fun parseSilentMovie(content: String): String? = GroupPlayClassicPolicy.parseSilentMovie(content)

    fun formatColorWord(pair: String, hostLabel: String): String = GroupPlayClassicPolicy.formatColorWord(pair, hostLabel)

    fun parseColorWord(content: String): String? = GroupPlayClassicPolicy.parseColorWord(content)

    const val DEBATE_FLASH_PREFIX = "DEBATEFLASH:"
    const val QUICK_POLL_PREFIX = "QUICKPOLL:"

    fun formatDebateFlash(topic: String, hostLabel: String): String = GroupPlayClassicPolicy.formatDebateFlash(topic, hostLabel)

    fun parseDebateFlash(content: String): String? = GroupPlayClassicPolicy.parseDebateFlash(content)

    fun formatQuickPoll(options: String, hostLabel: String): String = GroupPlayClassicPolicy.formatQuickPoll(options, hostLabel)

    fun parseQuickPoll(content: String): String? = GroupPlayClassicPolicy.parseQuickPoll(content)

    const val MIRROR_ECHO_PREFIX = "MIRRORECHO:"
    const val SYNC_CLAP_PREFIX = "SYNCCLAP:"
    const val FACT_OR_FICTION_PREFIX = "FACTORFICTION:"

    fun formatMirrorEcho(line: String, hostLabel: String): String = GroupPlayClassicPolicy.formatMirrorEcho(line, hostLabel)

    fun parseMirrorEcho(content: String): String? = GroupPlayClassicPolicy.parseMirrorEcho(content)

    fun formatSyncClap(count: String, hostLabel: String): String = GroupPlayClassicPolicy.formatSyncClap(count, hostLabel)

    fun parseSyncClap(content: String): String? = GroupPlayClassicPolicy.parseSyncClap(content)

    fun formatFactOrFiction(item: String, hostLabel: String): String = GroupPlayClassicPolicy.formatFactOrFiction(item, hostLabel)

    fun parseFactOrFiction(content: String): String? = GroupPlayClassicPolicy.parseFactOrFiction(content)

    const val IMPULSE_DRAW_PREFIX = "IMPULSEDRAW:"
    const val WORD_SCRAMBLE_PREFIX = "WORDSCRAMBLE:"
    const val REACTION_DUEL_PREFIX = "REACTDUEL:"

    fun formatImpulseDraw(token: String, hostLabel: String): String = GroupPlayClassicPolicy.formatImpulseDraw(token, hostLabel)

    fun parseImpulseDraw(content: String): String? = GroupPlayClassicPolicy.parseImpulseDraw(content)

    fun formatWordScramble(pair: String, hostLabel: String): String = GroupPlayClassicPolicy.formatWordScramble(pair, hostLabel)

    fun parseWordScramble(content: String): String? = GroupPlayClassicPolicy.parseWordScramble(content)

    fun formatReactionDuel(pair: String, hostLabel: String): String = GroupPlayClassicPolicy.formatReactionDuel(pair, hostLabel)

    fun parseReactionDuel(content: String): String? = GroupPlayClassicPolicy.parseReactionDuel(content)

    const val CODE_BREAKER_PREFIX = "CODEBREAKER:"
    const val SILLY_LAW_PREFIX = "SILLYLAW:"
    const val EMOJI_MATH_PREFIX = "EMOJIMATH:"

    fun formatCodeBreaker(code: String, hostLabel: String): String = GroupPlayClassicPolicy.formatCodeBreaker(code, hostLabel)

    fun parseCodeBreaker(content: String): String? = GroupPlayClassicPolicy.parseCodeBreaker(content)

    fun formatSillyLaw(law: String, hostLabel: String): String = GroupPlayClassicPolicy.formatSillyLaw(law, hostLabel)

    fun parseSillyLaw(content: String): String? = GroupPlayClassicPolicy.parseSillyLaw(content)

    fun formatEmojiMath(expr: String, hostLabel: String): String = GroupPlayClassicPolicy.formatEmojiMath(expr, hostLabel)

    fun parseEmojiMath(content: String): String? = GroupPlayClassicPolicy.parseEmojiMath(content)

    const val PIN_THE_MOOD_PREFIX = "PINTHEMOOD:"
    const val REVOKE_RUSH_PREFIX = "REVOKERUSH:"
    const val SECRET_SIGNAL_PREFIX = "SECRETSIGNAL:"

    fun formatPinTheMood(mood: String, hostLabel: String): String = GroupPlayClassicPolicy.formatPinTheMood(mood, hostLabel)

    fun parsePinTheMood(content: String): String? = GroupPlayClassicPolicy.parsePinTheMood(content)

    fun formatRevokeRush(window: String, hostLabel: String): String = GroupPlayClassicPolicy.formatRevokeRush(window, hostLabel)

    fun parseRevokeRush(content: String): String? = GroupPlayClassicPolicy.parseRevokeRush(content)

    fun formatSecretSignal(signal: String, hostLabel: String): String = GroupPlayClassicPolicy.formatSecretSignal(signal, hostLabel)

    fun parseSecretSignal(content: String): String? = GroupPlayClassicPolicy.parseSecretSignal(content)

    const val IDEA_RELAY_PREFIX = "IDEARELAY:"
    const val TEMPO_TAP_PREFIX = "TEMPOTAP:"
    const val TRANSLATE_RELAY_PREFIX = "TRANSRELAY:"

    fun formatIdeaRelay(seed: String, hostLabel: String): String = GroupPlayClassicPolicy.formatIdeaRelay(seed, hostLabel)

    fun parseIdeaRelay(content: String): String? = GroupPlayClassicPolicy.parseIdeaRelay(content)

    fun formatTempoTap(beat: String, hostLabel: String): String = GroupPlayClassicPolicy.formatTempoTap(beat, hostLabel)

    fun parseTempoTap(content: String): String? = GroupPlayClassicPolicy.parseTempoTap(content)

    fun formatTranslateRelay(pair: String, hostLabel: String): String = GroupPlayClassicPolicy.formatTranslateRelay(pair, hostLabel)

    fun parseTranslateRelay(content: String): String? = GroupPlayClassicPolicy.parseTranslateRelay(content)

    const val INVITE_RACE_PREFIX = "INVITERACE:"
    const val MENTION_MAYHEM_PREFIX = "MENTIONMAY:"
    const val LINK_HUNT_PREFIX = "LINKHUNT:"

    fun formatInviteRace(mode: String, hostLabel: String): String = GroupPlayClassicPolicy.formatInviteRace(mode, hostLabel)

    fun parseInviteRace(content: String): String? = GroupPlayClassicPolicy.parseInviteRace(content)

    fun formatMentionMayhem(mode: String, hostLabel: String): String = GroupPlayClassicPolicy.formatMentionMayhem(mode, hostLabel)

    fun parseMentionMayhem(content: String): String? = GroupPlayClassicPolicy.parseMentionMayhem(content)

    fun formatLinkHunt(mode: String, hostLabel: String): String = GroupPlayClassicPolicy.formatLinkHunt(mode, hostLabel)

    fun parseLinkHunt(content: String): String? = GroupPlayClassicPolicy.parseLinkHunt(content)

    const val NUDGE_DASH_PREFIX = "NUDGEDASH:"
    const val CODE_CHECK_PREFIX = "CODECHECK:"
    const val TRUST_SPRINT_PREFIX = "TRUSTSPRINT:"

    fun formatNudgeDash(mode: String, hostLabel: String): String = GroupPlayClassicPolicy.formatNudgeDash(mode, hostLabel)

    fun parseNudgeDash(content: String): String? = GroupPlayClassicPolicy.parseNudgeDash(content)

    fun formatCodeCheck(mode: String, hostLabel: String): String = GroupPlayClassicPolicy.formatCodeCheck(mode, hostLabel)

    fun parseCodeCheck(content: String): String? = GroupPlayClassicPolicy.parseCodeCheck(content)

    fun formatTrustSprint(mode: String, hostLabel: String): String = GroupPlayClassicPolicy.formatTrustSprint(mode, hostLabel)

    fun parseTrustSprint(content: String): String? = GroupPlayClassicPolicy.parseTrustSprint(content)

    const val MOOD_METER_PREFIX = "MOODMETER:"
    const val FOCUS_SPRINT_PREFIX = "FOCUSPRINT:"
    const val GRATITUDE_ROUND_PREFIX = "GRATROUND:"

    fun formatMoodMeter(scale: String, hostLabel: String): String = GroupPlayClassicPolicy.formatMoodMeter(scale, hostLabel)

    fun parseMoodMeter(content: String): String? = GroupPlayClassicPolicy.parseMoodMeter(content)

    fun formatFocusSprint(window: String, hostLabel: String): String = GroupPlayClassicPolicy.formatFocusSprint(window, hostLabel)

    fun parseFocusSprint(content: String): String? = GroupPlayClassicPolicy.parseFocusSprint(content)

    fun formatGratitudeRound(prompt: String, hostLabel: String): String = GroupPlayClassicPolicy.formatGratitudeRound(prompt, hostLabel)

    fun parseGratitudeRound(content: String): String? = GroupPlayClassicPolicy.parseGratitudeRound(content)

    const val QR_QUEST_PREFIX = "QRQUEST:"
    const val CONTACT_SWAP_PREFIX = "CONTACTSWAP:"
    const val SCAN_SPRINT_PREFIX = "SCANSPRINT:"

    fun formatQrQuest(mode: String, hostLabel: String): String = GroupPlayClassicPolicy.formatQrQuest(mode, hostLabel)

    fun parseQrQuest(content: String): String? = GroupPlayClassicPolicy.parseQrQuest(content)

    fun formatContactSwap(mode: String, hostLabel: String): String = GroupPlayClassicPolicy.formatContactSwap(mode, hostLabel)

    fun parseContactSwap(content: String): String? = GroupPlayClassicPolicy.parseContactSwap(content)

    fun formatScanSprint(mode: String, hostLabel: String): String = GroupPlayClassicPolicy.formatScanSprint(mode, hostLabel)

    fun parseScanSprint(content: String): String? = GroupPlayClassicPolicy.parseScanSprint(content)

    const val SPOILER_RACE_PREFIX = "SPOILERRACE:"
    const val BLUR_BATTLE_PREFIX = "BLURBATTLE:"
    const val DOWNLOAD_DASH_PREFIX = "DLDASH:"

    fun formatSpoilerRace(mode: String, hostLabel: String): String = GroupPlayClassicPolicy.formatSpoilerRace(mode, hostLabel)
    fun parseSpoilerRace(content: String): String? = GroupPlayClassicPolicy.parseSpoilerRace(content)
    fun formatBlurBattle(mode: String, hostLabel: String): String = GroupPlayClassicPolicy.formatBlurBattle(mode, hostLabel)
    fun parseBlurBattle(content: String): String? = GroupPlayClassicPolicy.parseBlurBattle(content)
    fun formatDownloadDash(mode: String, hostLabel: String): String = GroupPlayClassicPolicy.formatDownloadDash(mode, hostLabel)
    fun parseDownloadDash(content: String): String? = GroupPlayClassicPolicy.parseDownloadDash(content)

    const val PIN_DROP_PREFIX = "PINDROP:"
    const val FILE_RELAY_PREFIX = "FILERELAY:"
    const val MAP_DASH_PREFIX = "MAPDASH:"
    const val VAULT_LOCK_PREFIX = "VAULTLOCK:"
    const val WATERMARK_HUNT_PREFIX = "WMHUNT:"
    const val SECURE_SPRINT_PREFIX = "SECURESPRINT:"

    fun formatPinDrop(mode: String, hostLabel: String): String = GroupPlayClassicPolicy.formatPinDrop(mode, hostLabel)
    fun parsePinDrop(content: String): String? = GroupPlayClassicPolicy.parsePinDrop(content)
    fun formatFileRelay(mode: String, hostLabel: String): String = GroupPlayClassicPolicy.formatFileRelay(mode, hostLabel)
    fun parseFileRelay(content: String): String? = GroupPlayClassicPolicy.parseFileRelay(content)
    fun formatMapDash(mode: String, hostLabel: String): String = GroupPlayClassicPolicy.formatMapDash(mode, hostLabel)
    fun parseMapDash(content: String): String? = GroupPlayClassicPolicy.parseMapDash(content)
    fun formatVaultLock(mode: String, hostLabel: String): String = GroupPlayClassicPolicy.formatVaultLock(mode, hostLabel)
    fun parseVaultLock(content: String): String? = GroupPlayClassicPolicy.parseVaultLock(content)
    fun formatWatermarkHunt(mode: String, hostLabel: String): String = GroupPlayClassicPolicy.formatWatermarkHunt(mode, hostLabel)
    fun parseWatermarkHunt(content: String): String? = GroupPlayClassicPolicy.parseWatermarkHunt(content)
    fun formatSecureSprint(mode: String, hostLabel: String): String = GroupPlayClassicPolicy.formatSecureSprint(mode, hostLabel)
    fun parseSecureSprint(content: String): String? = GroupPlayClassicPolicy.parseSecureSprint(content)

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

    fun formatPhotoRace(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatPhotoRace(mode, hostLabel)
    fun parsePhotoRace(content: String): String? = GroupPlayModePolicy.parsePhotoRace(content)
    fun formatClipDash(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatClipDash(mode, hostLabel)
    fun parseClipDash(content: String): String? = GroupPlayModePolicy.parseClipDash(content)
    fun formatFrameHunt(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatFrameHunt(mode, hostLabel)
    fun parseFrameHunt(content: String): String? = GroupPlayModePolicy.parseFrameHunt(content)
    fun formatSummaryCircle(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatSummaryCircle(mode, hostLabel)
    fun parseSummaryCircle(content: String): String? = GroupPlayModePolicy.parseSummaryCircle(content)
    fun formatRewriteRelay(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatRewriteRelay(mode, hostLabel)
    fun parseRewriteRelay(content: String): String? = GroupPlayModePolicy.parseRewriteRelay(content)
    fun formatPromptSprint(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatPromptSprint(mode, hostLabel)
    fun parsePromptSprint(content: String): String? = GroupPlayModePolicy.parsePromptSprint(content)

    fun formatSuggestCircle(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatSuggestCircle(mode, hostLabel)
    fun parseSuggestCircle(content: String): String? = GroupPlayModePolicy.parseSuggestCircle(content)
    fun formatVoiceRace(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatVoiceRace(mode, hostLabel)
    fun parseVoiceRace(content: String): String? = GroupPlayModePolicy.parseVoiceRace(content)
    fun formatReplySprint(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatReplySprint(mode, hostLabel)
    fun parseReplySprint(content: String): String? = GroupPlayModePolicy.parseReplySprint(content)

    fun formatPixelQuest(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatPixelQuest(mode, hostLabel)
    fun parsePixelQuest(content: String): String? = GroupPlayModePolicy.parsePixelQuest(content)
    fun parseAssistCircle(content: String): String? = GroupPlayModePolicy.parseAssistCircle(content)
    fun parseDecisionDash(content: String): String? = GroupPlayModePolicy.parseDecisionDash(content)

    fun formatDocHunt(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatDocHunt(mode, hostLabel)
    fun parseDocHunt(content: String): String? = GroupPlayModePolicy.parseDocHunt(content)
    fun parseMeaningRace(content: String): String? = GroupPlayModePolicy.parseMeaningRace(content)
    fun parseInsightSprint(content: String): String? = GroupPlayModePolicy.parseInsightSprint(content)
    fun formatGifRelay(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatGifRelay(mode, hostLabel)
    fun parseGifRelay(content: String): String? = GroupPlayModePolicy.parseGifRelay(content)
    fun formatMarkHunt(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatMarkHunt(mode, hostLabel)
    fun parseMarkHunt(content: String): String? = GroupPlayModePolicy.parseMarkHunt(content)
    fun formatLeakSprint(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatLeakSprint(mode, hostLabel)
    fun parseLeakSprint(content: String): String? = GroupPlayModePolicy.parseLeakSprint(content)
    fun formatVoiceRing(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatVoiceRing(mode, hostLabel)
    fun parseVoiceRing(content: String): String? = GroupPlayModePolicy.parseVoiceRing(content)
    fun formatVideoStage(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatVideoStage(mode, hostLabel)
    fun parseVideoStage(content: String): String? = GroupPlayModePolicy.parseVideoStage(content)
    fun formatRingDash(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatRingDash(mode, hostLabel)
    fun parseRingDash(content: String): String? = GroupPlayModePolicy.parseRingDash(content)
    fun formatWallPick(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatWallPick(mode, hostLabel)
    fun parseWallPick(content: String): String? = GroupPlayModePolicy.parseWallPick(content)
    fun formatFontRace(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatFontRace(mode, hostLabel)
    fun parseFontRace(content: String): String? = GroupPlayModePolicy.parseFontRace(content)
    fun formatThemeSprint(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatThemeSprint(mode, hostLabel)
    fun parseThemeSprint(content: String): String? = GroupPlayModePolicy.parseThemeSprint(content)
    fun formatUnreadRush(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatUnreadRush(mode, hostLabel)
    fun parseUnreadRush(content: String): String? = GroupPlayModePolicy.parseUnreadRush(content)
    fun formatRingChoir(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatRingChoir(mode, hostLabel)
    fun parseRingChoir(content: String): String? = GroupPlayModePolicy.parseRingChoir(content)
    fun formatAlertSprint(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatAlertSprint(mode, hostLabel)
    fun parseAlertSprint(content: String): String? = GroupPlayModePolicy.parseAlertSprint(content)
    fun formatSoundWave(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatSoundWave(mode, hostLabel)
    fun parseSoundWave(content: String): String? = GroupPlayModePolicy.parseSoundWave(content)
    fun formatPreviewMask(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatPreviewMask(mode, hostLabel)
    fun parsePreviewMask(content: String): String? = GroupPlayModePolicy.parsePreviewMask(content)
    fun formatBeepDash(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatBeepDash(mode, hostLabel)
    fun parseBeepDash(content: String): String? = GroupPlayModePolicy.parseBeepDash(content)
    fun formatPushRace(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatPushRace(mode, hostLabel)
    fun parsePushRace(content: String): String? = GroupPlayModePolicy.parsePushRace(content)
    fun formatRemindCircle(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatRemindCircle(mode, hostLabel)
    fun parseRemindCircle(content: String): String? = GroupPlayModePolicy.parseRemindCircle(content)
    fun formatWakeSprint(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatWakeSprint(mode, hostLabel)
    fun parseWakeSprint(content: String): String? = GroupPlayModePolicy.parseWakeSprint(content)
    fun formatQuietHour(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatQuietHour(mode, hostLabel)
    fun parseQuietHour(content: String): String? = GroupPlayModePolicy.parseQuietHour(content)
    fun formatOfflineHint(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatOfflineHint(mode, hostLabel)
    fun parseOfflineHint(content: String): String? = GroupPlayModePolicy.parseOfflineHint(content)
    fun formatFallbackDash(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatFallbackDash(mode, hostLabel)
    fun parseFallbackDash(content: String): String? = GroupPlayModePolicy.parseFallbackDash(content)
    fun formatClickBeat(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatClickBeat(mode, hostLabel)
    fun parseClickBeat(content: String): String? = GroupPlayModePolicy.parseClickBeat(content)
    fun formatBuzzRelay(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatBuzzRelay(mode, hostLabel)
    fun parseBuzzRelay(content: String): String? = GroupPlayModePolicy.parseBuzzRelay(content)
    fun formatFeelSprint(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatFeelSprint(mode, hostLabel)
    fun parseFeelSprint(content: String): String? = GroupPlayModePolicy.parseFeelSprint(content)
    fun formatSlideRace(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatSlideRace(mode, hostLabel)
    fun parseSlideRace(content: String): String? = GroupPlayModePolicy.parseSlideRace(content)
    fun formatFadeCircle(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatFadeCircle(mode, hostLabel)
    fun parseFadeCircle(content: String): String? = GroupPlayModePolicy.parseFadeCircle(content)
    fun formatSpringDash(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatSpringDash(mode, hostLabel)
    fun parseSpringDash(content: String): String? = GroupPlayModePolicy.parseSpringDash(content)
    fun formatSnapGuard(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatSnapGuard(mode, hostLabel)
    fun parseSnapGuard(content: String): String? = GroupPlayModePolicy.parseSnapGuard(content)
    fun formatRecentsHide(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatRecentsHide(mode, hostLabel)
    fun parseRecentsHide(content: String): String? = GroupPlayModePolicy.parseRecentsHide(content)
    fun formatShieldSprint(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatShieldSprint(mode, hostLabel)
    fun parseShieldSprint(content: String): String? = GroupPlayModePolicy.parseShieldSprint(content)
    fun formatCopyLock(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatCopyLock(mode, hostLabel)
    fun parseCopyLock(content: String): String? = GroupPlayModePolicy.parseCopyLock(content)
    fun formatExportSeal(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatExportSeal(mode, hostLabel)
    fun parseExportSeal(content: String): String? = GroupPlayModePolicy.parseExportSeal(content)
    fun formatLeakWall(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatLeakWall(mode, hostLabel)
    fun parseLeakWall(content: String): String? = GroupPlayModePolicy.parseLeakWall(content)
    fun formatForwardSeal(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatForwardSeal(mode, hostLabel)
    fun parseForwardSeal(content: String): String? = GroupPlayModePolicy.parseForwardSeal(content)
    fun formatChatExportLock(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatChatExportLock(mode, hostLabel)
    fun parseChatExportLock(content: String): String? = GroupPlayModePolicy.parseChatExportLock(content)
    fun formatVaultFence(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatVaultFence(mode, hostLabel)
    fun parseVaultFence(content: String): String? = GroupPlayModePolicy.parseVaultFence(content)
    fun formatSealSprint(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatSealSprint(mode, hostLabel)
    fun parseSealSprint(content: String): String? = GroupPlayModePolicy.parseSealSprint(content)
    fun formatPqxdhDash(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatPqxdhDash(mode, hostLabel)
    fun parsePqxdhDash(content: String): String? = GroupPlayModePolicy.parsePqxdhDash(content)
    fun formatCertRelay(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatCertRelay(mode, hostLabel)
    fun parseCertRelay(content: String): String? = GroupPlayModePolicy.parseCertRelay(content)
    fun formatMarkSprint(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatMarkSprint(mode, hostLabel)
    fun parseMarkSprint(content: String): String? = GroupPlayModePolicy.parseMarkSprint(content)
    fun formatFadeTimer(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatFadeTimer(mode, hostLabel)
    fun parseFadeTimer(content: String): String? = GroupPlayModePolicy.parseFadeTimer(content)
    fun formatStampRelay(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatStampRelay(mode, hostLabel)
    fun parseStampRelay(content: String): String? = GroupPlayModePolicy.parseStampRelay(content)
    fun formatLinkLock(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatLinkLock(mode, hostLabel)
    fun parseLinkLock(content: String): String? = GroupPlayModePolicy.parseLinkLock(content)
    fun formatPreviewMute(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatPreviewMute(mode, hostLabel)
    fun parsePreviewMute(content: String): String? = GroupPlayModePolicy.parsePreviewMute(content)
    fun formatUrlFence(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatUrlFence(mode, hostLabel)
    fun parseUrlFence(content: String): String? = GroupPlayModePolicy.parseUrlFence(content)
    fun formatNotifMask(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatNotifMask(mode, hostLabel)
    fun parseNotifMask(content: String): String? = GroupPlayModePolicy.parseNotifMask(content)
    fun formatListBlur(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatListBlur(mode, hostLabel)
    fun parseListBlur(content: String): String? = GroupPlayModePolicy.parseListBlur(content)
    fun formatTraySeal(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatTraySeal(mode, hostLabel)
    fun parseTraySeal(content: String): String? = GroupPlayModePolicy.parseTraySeal(content)
    fun formatReactLock(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatReactLock(mode, hostLabel)
    fun parseReactLock(content: String): String? = GroupPlayModePolicy.parseReactLock(content)
    fun formatStarSeal(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatStarSeal(mode, hostLabel)
    fun parseStarSeal(content: String): String? = GroupPlayModePolicy.parseStarSeal(content)
    fun formatMetaFence(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatMetaFence(mode, hostLabel)
    fun parseMetaFence(content: String): String? = GroupPlayModePolicy.parseMetaFence(content)
    fun formatTypingSeal(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatTypingSeal(mode, hostLabel)
    fun parseTypingSeal(content: String): String? = GroupPlayModePolicy.parseTypingSeal(content)
    fun formatReadSeal(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatReadSeal(mode, hostLabel)
    fun parseReadSeal(content: String): String? = GroupPlayModePolicy.parseReadSeal(content)
    fun formatPresenceSeal(mode: String, hostLabel: String): String = GroupPlayModePolicy.formatPresenceSeal(mode, hostLabel)
    fun parsePresenceSeal(content: String): String? = GroupPlaySealPolicy.parsePresenceSeal(content)
    fun formatLastSeenSeal(mode: String, hostLabel: String): String = GroupPlaySealPolicy.formatLastSeenSeal(mode, hostLabel)
    fun parseLastSeenSeal(content: String): String? = GroupPlaySealPolicy.parseLastSeenSeal(content)
}
