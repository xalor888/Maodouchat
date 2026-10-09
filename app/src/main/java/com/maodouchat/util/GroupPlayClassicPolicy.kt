package com.maodouchat.util

/**
 * 经典群玩法模式的编解码门面：实现已按游戏族拆到
 * `GroupPlayClassicDuel`（对决竞技）/`GroupPlayClassicQuiz`（问答知识）/
 * `GroupPlayClassicParty`（派对社交）/`GroupPlayClassicSprint`（竞速反应），
 * 这里只保留同名委托，调用方零改动。
 */
internal object GroupPlayClassicPolicy {

    fun rollRps(): String =
        GroupPlayClassicDuel.rollRps()

    fun formatRps(choice: String, userLabel: String): String =
        GroupPlayClassicDuel.formatRps(choice, userLabel)

    fun parseRps(content: String): String? =
        GroupPlayClassicDuel.parseRps(content)

    fun rollNumberBomb(max: Int = 100): Pair<Int, Int> =
        GroupPlayClassicDuel.rollNumberBomb(max)

    fun formatNumberBomb(secret: Int, max: Int, hostLabel: String): String =
        GroupPlayClassicDuel.formatNumberBomb(secret, max, hostLabel)

    fun parseNumberBomb(content: String): Triple<Int, Int, String>? =
        GroupPlayClassicDuel.parseNumberBomb(content)

    fun randomWordSeed(): String =
        GroupPlayClassicParty.randomWordSeed()

    fun formatWordChain(seed: String, userLabel: String): String =
        GroupPlayClassicParty.formatWordChain(seed, userLabel)

    fun randomRaceToken(): String =
        GroupPlayClassicDuel.randomRaceToken()

    fun formatReactionRace(token: String, hostLabel: String): String =
        GroupPlayClassicDuel.formatReactionRace(token, hostLabel)

    fun parseReactionRace(content: String): Pair<String, String>? =
        GroupPlayClassicDuel.parseReactionRace(content)

    fun formatWouldYouRather(a: String, b: String, hostLabel: String): String =
        GroupPlayClassicParty.formatWouldYouRather(a, b, hostLabel)

    fun parseWouldYouRather(content: String): Triple<String, String, String>? =
        GroupPlayClassicParty.parseWouldYouRather(content)

    fun formatEmojiRain(hostLabel: String, emoji: String = rainEmojis.random()): String =
        GroupPlayClassicParty.formatEmojiRain(hostLabel, emoji)

    fun parseEmojiRain(content: String): Pair<String, String>? =
        GroupPlayClassicParty.parseEmojiRain(content)

    fun formatTwoTruthsOneLie(t1: String, t2: String, lie: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatTwoTruthsOneLie(t1, t2, lie, hostLabel)

    fun parseTwoTruthsOneLie(content: String): List<String>? =
        GroupPlayClassicQuiz.parseTwoTruthsOneLie(content)

    fun formatQuiz(question: String, answer: String, options: List<String>, hostLabel: String): String =
        GroupPlayClassicQuiz.formatQuiz(question, answer, options, hostLabel)

    fun parseQuiz(content: String): Triple<String, String, List<String>>? =
        GroupPlayClassicQuiz.parseQuiz(content)

    fun spinWheel(): String =
        GroupPlayClassicDuel.spinWheel()

    fun formatSpin(result: String, hostLabel: String): String =
        GroupPlayClassicDuel.formatSpin(result, hostLabel)

    fun parseSpin(content: String): String? =
        GroupPlayClassicDuel.parseSpin(content)

    fun formatStory(seed: String, hostLabel: String): String =
        GroupPlayClassicParty.formatStory(seed, hostLabel)

    fun parseStory(content: String): String? =
        GroupPlayClassicParty.parseStory(content)

    fun formatCountdown(seconds: Int, hostLabel: String): String =
        GroupPlayClassicSprint.formatCountdown(seconds, hostLabel)

    fun parseCountdown(content: String): Int? =
        GroupPlayClassicSprint.parseCountdown(content)

    fun formatBingo(board: List<String>, hostLabel: String): String =
        GroupPlayClassicDuel.formatBingo(board, hostLabel)

    fun parseBingo(content: String): List<String>? =
        GroupPlayClassicDuel.parseBingo(content)

    fun formatLottery(pool: List<String>, winner: String, hostLabel: String): String =
        GroupPlayClassicDuel.formatLottery(pool, winner, hostLabel)

    fun parseLottery(content: String): Pair<String, List<String>>? =
        GroupPlayClassicDuel.parseLottery(content)

    fun formatHotSeat(target: String, hostLabel: String): String =
        GroupPlayClassicParty.formatHotSeat(target, hostLabel)

    fun parseHotSeat(content: String): String? =
        GroupPlayClassicParty.parseHotSeat(content)

    fun formatCoinFlip(side: String, hostLabel: String): String =
        GroupPlayClassicDuel.formatCoinFlip(side, hostLabel)

    fun parseCoinFlip(content: String): String? =
        GroupPlayClassicDuel.parseCoinFlip(content)

    fun formatRedPacketJoke(amountLabel: String, hostLabel: String): String =
        GroupPlayClassicParty.formatRedPacketJoke(amountLabel, hostLabel)

    fun parseRedPacketJoke(content: String): String? =
        GroupPlayClassicParty.parseRedPacketJoke(content)

    fun formatCharades(prompt: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatCharades(prompt, hostLabel)

    fun parseCharades(content: String): String? =
        GroupPlayClassicQuiz.parseCharades(content)

    fun formatNumberGuess(secret: Int, max: Int, hostLabel: String): String =
        GroupPlayClassicQuiz.formatNumberGuess(secret, max, hostLabel)

    fun parseNumberGuess(content: String): Pair<Int, Int>? =
        GroupPlayClassicQuiz.parseNumberGuess(content)

    fun formatRiddle(q: String, a: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatRiddle(q, a, hostLabel)

    fun parseRiddle(content: String): Pair<String, String>? =
        GroupPlayClassicQuiz.parseRiddle(content)

    fun formatImpostor(word: String, hostLabel: String): String =
        GroupPlayClassicParty.formatImpostor(word, hostLabel)

    fun parseImpostor(content: String): String? =
        GroupPlayClassicParty.parseImpostor(content)

    fun formatEmojiStory(seed: String, hostLabel: String): String =
        GroupPlayClassicParty.formatEmojiStory(seed, hostLabel)

    fun parseEmojiStory(content: String): String? =
        GroupPlayClassicParty.parseEmojiStory(content)

    fun formatSimon(seq: String, hostLabel: String): String =
        GroupPlayClassicDuel.formatSimon(seq, hostLabel)

    fun parseSimon(content: String): String? =
        GroupPlayClassicDuel.parseSimon(content)

    fun formatHotOrNot(topic: String, hostLabel: String): String =
        GroupPlayClassicParty.formatHotOrNot(topic, hostLabel)

    fun parseHotOrNot(content: String): String? =
        GroupPlayClassicParty.parseHotOrNot(content)

    fun formatAlphabet(letter: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatAlphabet(letter, hostLabel)

    fun parseAlphabet(content: String): String? =
        GroupPlayClassicQuiz.parseAlphabet(content)

    fun formatTrivia(q: String, a: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatTrivia(q, a, hostLabel)

    fun parseTrivia(content: String): Pair<String, String>? =
        GroupPlayClassicQuiz.parseTrivia(content)

    fun formatSpeedChallenge(sec: Int, hostLabel: String): String =
        GroupPlayClassicSprint.formatSpeedChallenge(sec, hostLabel)

    fun parseSpeedChallenge(content: String): Int? =
        GroupPlayClassicSprint.parseSpeedChallenge(content)

    fun randomMemoryBoard(): String =
        GroupPlayClassicParty.randomMemoryBoard()

    fun formatTruthOrDare(mode: String, prompt: String, hostLabel: String): String =
        GroupPlayClassicParty.formatTruthOrDare(mode, prompt, hostLabel)

    fun parseTruthOrDare(content: String): Pair<String, String>? =
        GroupPlayClassicParty.parseTruthOrDare(content)

    fun formatNeverHaveIEver(prompt: String, hostLabel: String): String =
        GroupPlayClassicParty.formatNeverHaveIEver(prompt, hostLabel)

    fun parseNeverHaveIEver(content: String): String? =
        GroupPlayClassicParty.parseNeverHaveIEver(content)

    fun formatMemoryMatch(board: String, hostLabel: String): String =
        GroupPlayClassicParty.formatMemoryMatch(board, hostLabel)

    fun parseMemoryMatch(content: String): String? =
        GroupPlayClassicParty.parseMemoryMatch(content)

    fun formatDrawPrompt(prompt: String, hostLabel: String): String =
        GroupPlayClassicParty.formatDrawPrompt(prompt, hostLabel)

    fun parseDrawPrompt(content: String): String? =
        GroupPlayClassicParty.parseDrawPrompt(content)

    fun randomEmojiDuel(): String =
        GroupPlayClassicDuel.randomEmojiDuel()

    fun formatIcebreaker(prompt: String, hostLabel: String): String =
        GroupPlayClassicParty.formatIcebreaker(prompt, hostLabel)

    fun parseIcebreaker(content: String): String? =
        GroupPlayClassicParty.parseIcebreaker(content)

    fun formatEmojiDuel(pair: String, hostLabel: String): String =
        GroupPlayClassicDuel.formatEmojiDuel(pair, hostLabel)

    fun parseEmojiDuel(content: String): String? =
        GroupPlayClassicDuel.parseEmojiDuel(content)

    fun formatRapidFire(topic: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatRapidFire(topic, hostLabel)

    fun parseRapidFire(content: String): String? =
        GroupPlayClassicSprint.parseRapidFire(content)

    fun formatScatter(letter: String, category: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatScatter(letter, category, hostLabel)

    fun parseScatter(content: String): Pair<String, String>? =
        GroupPlayClassicQuiz.parseScatter(content)

    fun formatMinuteTalk(topic: String, hostLabel: String): String =
        GroupPlayClassicParty.formatMinuteTalk(topic, hostLabel)

    fun parseMinuteTalk(content: String): String? =
        GroupPlayClassicParty.parseMinuteTalk(content)

    fun formatCaptionThis(seed: String, hostLabel: String): String =
        GroupPlayClassicParty.formatCaptionThis(seed, hostLabel)

    fun parseCaptionThis(content: String): String? =
        GroupPlayClassicParty.parseCaptionThis(content)

    fun formatStorySwap(opener: String, hostLabel: String): String =
        GroupPlayClassicParty.formatStorySwap(opener, hostLabel)

    fun parseStorySwap(content: String): String? =
        GroupPlayClassicParty.parseStorySwap(content)

    fun formatKaraoke(line: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatKaraoke(line, hostLabel)

    fun parseKaraoke(content: String): String? =
        GroupPlayClassicQuiz.parseKaraoke(content)

    fun formatBlindQ(q: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatBlindQ(q, hostLabel)

    fun parseBlindQ(content: String): String? =
        GroupPlayClassicQuiz.parseBlindQ(content)

    fun randomChainSeed(): String =
        GroupPlayClassicParty.randomChainSeed()

    fun formatFortune(text: String, hostLabel: String): String =
        GroupPlayClassicParty.formatFortune(text, hostLabel)

    fun parseFortune(content: String): String? =
        GroupPlayClassicParty.parseFortune(content)

    fun formatEmojiQuiz(prompt: String, answer: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatEmojiQuiz(prompt, answer, hostLabel)

    fun parseEmojiQuiz(content: String): Pair<String, String>? =
        GroupPlayClassicQuiz.parseEmojiQuiz(content)

    fun formatChainReact(seed: String, hostLabel: String): String =
        GroupPlayClassicParty.formatChainReact(seed, hostLabel)

    fun parseChainReact(content: String): String? =
        GroupPlayClassicParty.parseChainReact(content)

    fun randomHideEmoji(): String =
        GroupPlayClassicParty.randomHideEmoji()

    fun formatDebate(topic: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatDebate(topic, hostLabel)

    fun parseDebate(content: String): String? =
        GroupPlayClassicQuiz.parseDebate(content)

    fun formatMirror(line: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatMirror(line, hostLabel)

    fun parseMirror(content: String): String? =
        GroupPlayClassicQuiz.parseMirror(content)

    fun formatHideSeek(emoji: String, hostLabel: String): String =
        GroupPlayClassicParty.formatHideSeek(emoji, hostLabel)

    fun parseHideSeek(content: String): String? =
        GroupPlayClassicParty.parseHideSeek(content)

    fun formatToast(line: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatToast(line, hostLabel)

    fun parseToast(content: String): String? =
        GroupPlayClassicQuiz.parseToast(content)

    fun formatHotPotato(seconds: Int, hostLabel: String): String =
        GroupPlayClassicDuel.formatHotPotato(seconds, hostLabel)

    fun parseHotPotato(content: String): Int? =
        GroupPlayClassicDuel.parseHotPotato(content)

    fun formatWordHint(hint: String, answer: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatWordHint(hint, answer, hostLabel)

    fun parseWordHint(content: String): Pair<String, String>? =
        GroupPlayClassicQuiz.parseWordHint(content)

    fun formatSpyfall(location: String, hostLabel: String): String =
        GroupPlayClassicParty.formatSpyfall(location, hostLabel)

    fun parseSpyfall(content: String): String? =
        GroupPlayClassicParty.parseSpyfall(content)

    fun formatAcrostic(seed: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatAcrostic(seed, hostLabel)

    fun parseAcrostic(content: String): String? =
        GroupPlayClassicQuiz.parseAcrostic(content)

    fun formatEmojiTranslate(prompt: String, answer: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatEmojiTranslate(prompt, answer, hostLabel)

    fun parseEmojiTranslate(content: String): Pair<String, String>? =
        GroupPlayClassicQuiz.parseEmojiTranslate(content)

    fun formatTwentyQuestions(subject: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatTwentyQuestions(subject, hostLabel)

    fun parseTwentyQuestions(content: String): String? =
        GroupPlayClassicQuiz.parseTwentyQuestions(content)

    fun formatRhyme(seed: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatRhyme(seed, hostLabel)

    fun parseRhyme(content: String): String? =
        GroupPlayClassicQuiz.parseRhyme(content)

    fun formatOddOneOut(options: String, answer: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatOddOneOut(options, answer, hostLabel)

    fun parseOddOneOut(content: String): Pair<String, String>? =
        GroupPlayClassicQuiz.parseOddOneOut(content)

    fun formatCategories(cat: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatCategories(cat, hostLabel)

    fun parseCategories(content: String): String? =
        GroupPlayClassicQuiz.parseCategories(content)

    fun formatPasswordGame(hint: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatPasswordGame(hint, hostLabel)

    fun parsePasswordGame(content: String): String? =
        GroupPlayClassicQuiz.parsePasswordGame(content)

    fun formatTimeCapsule(note: String, hostLabel: String): String =
        GroupPlayClassicParty.formatTimeCapsule(note, hostLabel)

    fun parseTimeCapsule(content: String): String? =
        GroupPlayClassicParty.parseTimeCapsule(content)

    fun formatTaboo(card: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatTaboo(card, hostLabel)

    fun parseTaboo(content: String): String? =
        GroupPlayClassicQuiz.parseTaboo(content)

    fun formatLightning(prompt: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatLightning(prompt, hostLabel)

    fun parseLightning(content: String): String? =
        GroupPlayClassicSprint.parseLightning(content)

    fun formatTwoWords(seed: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatTwoWords(seed, hostLabel)

    fun parseTwoWords(content: String): String? =
        GroupPlayClassicQuiz.parseTwoWords(content)

    fun formatWhisper(prompt: String, hostLabel: String): String =
        GroupPlayClassicParty.formatWhisper(prompt, hostLabel)

    fun parseWhisper(content: String): String? =
        GroupPlayClassicParty.parseWhisper(content)

    fun formatCountdownRace(seconds: Int, hostLabel: String): String =
        GroupPlayClassicDuel.formatCountdownRace(seconds, hostLabel)

    fun parseCountdownRace(content: String): String? =
        GroupPlayClassicDuel.parseCountdownRace(content)

    fun formatEmojiMemory(board: String, hostLabel: String): String =
        GroupPlayClassicParty.formatEmojiMemory(board, hostLabel)

    fun parseEmojiMemory(content: String): String? =
        GroupPlayClassicParty.parseEmojiMemory(content)

    fun formatGeoGuess(clue: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatGeoGuess(clue, hostLabel)

    fun parseGeoGuess(content: String): String? =
        GroupPlayClassicQuiz.parseGeoGuess(content)

    fun formatOneWord(word: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatOneWord(word, hostLabel)

    fun parseOneWord(content: String): String? =
        GroupPlayClassicQuiz.parseOneWord(content)

    fun formatSpeedMath(q: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatSpeedMath(q, hostLabel)

    fun parseSpeedMath(content: String): String? =
        GroupPlayClassicQuiz.parseSpeedMath(content)

    fun formatStorySeed(seed: String, hostLabel: String): String =
        GroupPlayClassicParty.formatStorySeed(seed, hostLabel)

    fun parseStorySeed(content: String): String? =
        GroupPlayClassicParty.parseStorySeed(content)

    fun formatWould2(a: String, b: String, hostLabel: String): String =
        GroupPlayClassicParty.formatWould2(a, b, hostLabel)

    fun parseWould2(content: String): Pair<String, String>? =
        GroupPlayClassicParty.parseWould2(content)

    fun formatEmojiOnly(prompt: String, hostLabel: String): String =
        GroupPlayClassicParty.formatEmojiOnly(prompt, hostLabel)

    fun parseEmojiOnly(content: String): String? =
        GroupPlayClassicParty.parseEmojiOnly(content)

    fun formatBlindDraw(token: String, hostLabel: String): String =
        GroupPlayClassicParty.formatBlindDraw(token, hostLabel)

    fun parseBlindDraw(content: String): String? =
        GroupPlayClassicParty.parseBlindDraw(content)

    fun formatAlphabetRace(start: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatAlphabetRace(start, hostLabel)

    fun parseAlphabetRace(content: String): String? =
        GroupPlayClassicQuiz.parseAlphabetRace(content)

    fun formatSilentMovie(prompt: String, hostLabel: String): String =
        GroupPlayClassicParty.formatSilentMovie(prompt, hostLabel)

    fun parseSilentMovie(content: String): String? =
        GroupPlayClassicParty.parseSilentMovie(content)

    fun formatColorWord(pair: String, hostLabel: String): String =
        GroupPlayClassicParty.formatColorWord(pair, hostLabel)

    fun parseColorWord(content: String): String? =
        GroupPlayClassicParty.parseColorWord(content)

    fun formatDebateFlash(topic: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatDebateFlash(topic, hostLabel)

    fun parseDebateFlash(content: String): String? =
        GroupPlayClassicQuiz.parseDebateFlash(content)

    fun formatQuickPoll(options: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatQuickPoll(options, hostLabel)

    fun parseQuickPoll(content: String): String? =
        GroupPlayClassicQuiz.parseQuickPoll(content)

    fun formatMirrorEcho(line: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatMirrorEcho(line, hostLabel)

    fun parseMirrorEcho(content: String): String? =
        GroupPlayClassicQuiz.parseMirrorEcho(content)

    fun formatSyncClap(count: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatSyncClap(count, hostLabel)

    fun parseSyncClap(content: String): String? =
        GroupPlayClassicSprint.parseSyncClap(content)

    fun formatFactOrFiction(item: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatFactOrFiction(item, hostLabel)

    fun parseFactOrFiction(content: String): String? =
        GroupPlayClassicQuiz.parseFactOrFiction(content)

    fun formatImpulseDraw(token: String, hostLabel: String): String =
        GroupPlayClassicParty.formatImpulseDraw(token, hostLabel)

    fun parseImpulseDraw(content: String): String? =
        GroupPlayClassicParty.parseImpulseDraw(content)

    fun formatWordScramble(pair: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatWordScramble(pair, hostLabel)

    fun parseWordScramble(content: String): String? =
        GroupPlayClassicQuiz.parseWordScramble(content)

    fun formatReactionDuel(pair: String, hostLabel: String): String =
        GroupPlayClassicDuel.formatReactionDuel(pair, hostLabel)

    fun parseReactionDuel(content: String): String? =
        GroupPlayClassicDuel.parseReactionDuel(content)

    fun formatCodeBreaker(code: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatCodeBreaker(code, hostLabel)

    fun parseCodeBreaker(content: String): String? =
        GroupPlayClassicQuiz.parseCodeBreaker(content)

    fun formatSillyLaw(law: String, hostLabel: String): String =
        GroupPlayClassicParty.formatSillyLaw(law, hostLabel)

    fun parseSillyLaw(content: String): String? =
        GroupPlayClassicParty.parseSillyLaw(content)

    fun formatEmojiMath(expr: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatEmojiMath(expr, hostLabel)

    fun parseEmojiMath(content: String): String? =
        GroupPlayClassicQuiz.parseEmojiMath(content)

    fun formatPinTheMood(mood: String, hostLabel: String): String =
        GroupPlayClassicParty.formatPinTheMood(mood, hostLabel)

    fun parsePinTheMood(content: String): String? =
        GroupPlayClassicParty.parsePinTheMood(content)

    fun formatRevokeRush(window: String, hostLabel: String): String =
        GroupPlayClassicParty.formatRevokeRush(window, hostLabel)

    fun parseRevokeRush(content: String): String? =
        GroupPlayClassicParty.parseRevokeRush(content)

    fun formatSecretSignal(signal: String, hostLabel: String): String =
        GroupPlayClassicParty.formatSecretSignal(signal, hostLabel)

    fun parseSecretSignal(content: String): String? =
        GroupPlayClassicParty.parseSecretSignal(content)

    fun formatIdeaRelay(seed: String, hostLabel: String): String =
        GroupPlayClassicParty.formatIdeaRelay(seed, hostLabel)

    fun parseIdeaRelay(content: String): String? =
        GroupPlayClassicParty.parseIdeaRelay(content)

    fun formatTempoTap(beat: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatTempoTap(beat, hostLabel)

    fun parseTempoTap(content: String): String? =
        GroupPlayClassicSprint.parseTempoTap(content)

    fun formatTranslateRelay(pair: String, hostLabel: String): String =
        GroupPlayClassicQuiz.formatTranslateRelay(pair, hostLabel)

    fun parseTranslateRelay(content: String): String? =
        GroupPlayClassicQuiz.parseTranslateRelay(content)

    fun formatInviteRace(mode: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatInviteRace(mode, hostLabel)

    fun parseInviteRace(content: String): String? =
        GroupPlayClassicSprint.parseInviteRace(content)

    fun formatMentionMayhem(mode: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatMentionMayhem(mode, hostLabel)

    fun parseMentionMayhem(content: String): String? =
        GroupPlayClassicSprint.parseMentionMayhem(content)

    fun formatLinkHunt(mode: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatLinkHunt(mode, hostLabel)

    fun parseLinkHunt(content: String): String? =
        GroupPlayClassicSprint.parseLinkHunt(content)

    fun formatNudgeDash(mode: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatNudgeDash(mode, hostLabel)

    fun parseNudgeDash(content: String): String? =
        GroupPlayClassicSprint.parseNudgeDash(content)

    fun formatCodeCheck(mode: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatCodeCheck(mode, hostLabel)

    fun parseCodeCheck(content: String): String? =
        GroupPlayClassicSprint.parseCodeCheck(content)

    fun formatTrustSprint(mode: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatTrustSprint(mode, hostLabel)

    fun parseTrustSprint(content: String): String? =
        GroupPlayClassicSprint.parseTrustSprint(content)

    fun formatMoodMeter(scale: String, hostLabel: String): String =
        GroupPlayClassicParty.formatMoodMeter(scale, hostLabel)

    fun parseMoodMeter(content: String): String? =
        GroupPlayClassicParty.parseMoodMeter(content)

    fun formatFocusSprint(window: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatFocusSprint(window, hostLabel)

    fun parseFocusSprint(content: String): String? =
        GroupPlayClassicSprint.parseFocusSprint(content)

    fun formatGratitudeRound(prompt: String, hostLabel: String): String =
        GroupPlayClassicParty.formatGratitudeRound(prompt, hostLabel)

    fun parseGratitudeRound(content: String): String? =
        GroupPlayClassicParty.parseGratitudeRound(content)

    fun formatQrQuest(mode: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatQrQuest(mode, hostLabel)

    fun parseQrQuest(content: String): String? =
        GroupPlayClassicSprint.parseQrQuest(content)

    fun formatContactSwap(mode: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatContactSwap(mode, hostLabel)

    fun parseContactSwap(content: String): String? =
        GroupPlayClassicSprint.parseContactSwap(content)

    fun formatScanSprint(mode: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatScanSprint(mode, hostLabel)

    fun parseScanSprint(content: String): String? =
        GroupPlayClassicSprint.parseScanSprint(content)

    fun formatSpoilerRace(mode: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatSpoilerRace(mode, hostLabel)

    fun parseSpoilerRace(content: String): String? =
        GroupPlayClassicSprint.parseSpoilerRace(content)

    fun formatBlurBattle(mode: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatBlurBattle(mode, hostLabel)

    fun parseBlurBattle(content: String): String? =
        GroupPlayClassicSprint.parseBlurBattle(content)

    fun formatDownloadDash(mode: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatDownloadDash(mode, hostLabel)

    fun parseDownloadDash(content: String): String? =
        GroupPlayClassicSprint.parseDownloadDash(content)

    fun formatPinDrop(mode: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatPinDrop(mode, hostLabel)

    fun parsePinDrop(content: String): String? =
        GroupPlayClassicSprint.parsePinDrop(content)

    fun formatFileRelay(mode: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatFileRelay(mode, hostLabel)

    fun parseFileRelay(content: String): String? =
        GroupPlayClassicSprint.parseFileRelay(content)

    fun formatMapDash(mode: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatMapDash(mode, hostLabel)

    fun parseMapDash(content: String): String? =
        GroupPlayClassicSprint.parseMapDash(content)

    fun formatVaultLock(mode: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatVaultLock(mode, hostLabel)

    fun parseVaultLock(content: String): String? =
        GroupPlayClassicSprint.parseVaultLock(content)

    fun formatWatermarkHunt(mode: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatWatermarkHunt(mode, hostLabel)

    fun parseWatermarkHunt(content: String): String? =
        GroupPlayClassicSprint.parseWatermarkHunt(content)

    fun formatSecureSprint(mode: String, hostLabel: String): String =
        GroupPlayClassicSprint.formatSecureSprint(mode, hostLabel)

    fun parseSecureSprint(content: String): String? =
        GroupPlayClassicSprint.parseSecureSprint(content)
}
