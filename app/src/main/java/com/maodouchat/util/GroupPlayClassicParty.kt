package com.maodouchat.util

// 派对社交类（表情玩法/社交破冰/创意接龙等）：编解码实现（从 GroupPlayClassicPolicy 逐字搬出，零行为改动）。
internal object GroupPlayClassicParty {

    fun randomWordSeed(): String = com.maodouchat.group.play.GroupChainPolicy.randomWordSeed()

    fun formatWordChain(seed: String, userLabel: String): String =
        com.maodouchat.group.play.GroupChainPolicy.formatWordChain(seed, userLabel)

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

    fun formatStory(seed: String, hostLabel: String): String {
        val s = seed.trim().ifBlank { "Once upon a time in a chat group..." }.take(160)
        return "${GroupPlayPolicy.STORY_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} started a story"
    }

    fun parseStory(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.STORY_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.STORY_PREFIX).substringBefore('|'))
    }

    fun formatHotSeat(target: String, hostLabel: String): String {
        val t = target.trim().ifBlank { "someone" }.take(40)
        return "${GroupPlayPolicy.HOTSEAT_PREFIX}${GroupPlayFieldEscape.esc(t)}|${hostLabel} put $t on the hot seat — ask a question!"
    }

    fun parseHotSeat(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.HOTSEAT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.HOTSEAT_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatRedPacketJoke(amountLabel: String, hostLabel: String): String {
        val a = amountLabel.trim().ifBlank { "lucky" }.take(24)
        return "${GroupPlayPolicy.REDPACKET_PREFIX}${GroupPlayFieldEscape.esc(a)}|${hostLabel} sent a fun red packet ($a) — claim in chat!"
    }

    fun parseRedPacketJoke(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.REDPACKET_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.REDPACKET_PREFIX).substringBefore('|'))
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

    fun formatHotOrNot(topic: String, hostLabel: String): String {
        val t = topic.trim().ifBlank { "this idea" }.take(60)
        return "${GroupPlayPolicy.HOTORNOT_PREFIX}${GroupPlayFieldEscape.esc(t)}|${hostLabel} hot or not: $t"
    }

    fun parseHotOrNot(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.HOTORNOT_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.HOTORNOT_PREFIX).substringBefore('|')).ifBlank { null }
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

    fun formatIcebreaker(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(100)
        return "${GroupPlayPolicy.ICEBREAKER_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} icebreaker"
    }

    fun parseIcebreaker(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.ICEBREAKER_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.ICEBREAKER_PREFIX).substringBefore('|')).ifBlank { null }
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

    fun randomChainSeed(): String = chainSeeds.random()

    fun formatFortune(text: String, hostLabel: String): String {
        val t = text.trim().take(80)
        return "${GroupPlayPolicy.FORTUNE_PREFIX}${GroupPlayFieldEscape.esc(t)}|${hostLabel} fortune cookie"
    }

    fun parseFortune(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.FORTUNE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.FORTUNE_PREFIX).substringBefore('|')).ifBlank { null }
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

    fun formatHideSeek(emoji: String, hostLabel: String): String {
        val e = emoji.trim().ifBlank { randomHideEmoji() }.take(8)
        return "${GroupPlayPolicy.HIDESEEK_PREFIX}${GroupPlayFieldEscape.esc(e)}|${hostLabel} hide & seek — find the emoji in chat history!"
    }

    fun parseHideSeek(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.HIDESEEK_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.HIDESEEK_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatSpyfall(location: String, hostLabel: String): String {
        val loc = location.trim().take(40)
        return "${GroupPlayPolicy.SPYFALL_PREFIX}${GroupPlayFieldEscape.esc(loc)}|${hostLabel} spyfall — one spy doesn't know the place!"
    }

    fun parseSpyfall(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SPYFALL_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SPYFALL_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatTimeCapsule(note: String, hostLabel: String): String {
        val n = note.trim().take(80)
        return "${GroupPlayPolicy.TIMECAPSULE_PREFIX}${GroupPlayFieldEscape.esc(n)}|${hostLabel} time capsule — open later"
    }

    fun parseTimeCapsule(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.TIMECAPSULE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.TIMECAPSULE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatWhisper(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(60)
        return "${GroupPlayPolicy.WHISPER_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} whisper challenge"
    }

    fun parseWhisper(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.WHISPER_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.WHISPER_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatEmojiMemory(board: String, hostLabel: String): String {
        val b = board.trim().take(24)
        return "${GroupPlayPolicy.EMOJI_MEMORY_PREFIX}${GroupPlayFieldEscape.esc(b)}|${hostLabel} emoji memory — memorize then recall"
    }

    fun parseEmojiMemory(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.EMOJI_MEMORY_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.EMOJI_MEMORY_PREFIX).substringBefore('|')).ifBlank { null }
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

    fun formatImpulseDraw(token: String, hostLabel: String): String {
        val tk = token.trim().take(8)
        return "${GroupPlayPolicy.IMPULSE_DRAW_PREFIX}${GroupPlayFieldEscape.esc(tk)}|${hostLabel} impulse draw"
    }

    fun parseImpulseDraw(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.IMPULSE_DRAW_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.IMPULSE_DRAW_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatSillyLaw(law: String, hostLabel: String): String {
        val l = law.trim().take(50)
        return "${GroupPlayPolicy.SILLY_LAW_PREFIX}${GroupPlayFieldEscape.esc(l)}|${hostLabel} silly law"
    }

    fun parseSillyLaw(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SILLY_LAW_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SILLY_LAW_PREFIX).substringBefore('|')).ifBlank { null }
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

    fun formatMoodMeter(scale: String, hostLabel: String): String {
        val s = scale.trim().take(30)
        return "${GroupPlayPolicy.MOOD_METER_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} mood meter"
    }

    fun parseMoodMeter(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.MOOD_METER_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.MOOD_METER_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatGratitudeRound(prompt: String, hostLabel: String): String {
        val p = prompt.trim().take(40)
        return "${GroupPlayPolicy.GRATITUDE_ROUND_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} gratitude round"
    }

    fun parseGratitudeRound(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.GRATITUDE_ROUND_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.GRATITUDE_ROUND_PREFIX).substringBefore('|')).ifBlank { null }
    }
}
