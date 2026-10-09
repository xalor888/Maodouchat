package com.maodouchat.util

// 对决竞技类（RPS/数字炸弹/反应竞速等）：编解码实现（从 GroupPlayClassicPolicy 逐字搬出，零行为改动）。
internal object GroupPlayClassicDuel {

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

    fun randomRaceToken(): String = com.maodouchat.group.play.GroupPkPolicy.randomRaceToken()

    fun formatReactionRace(token: String, hostLabel: String): String =
        com.maodouchat.group.play.GroupPkPolicy.formatReactionRace(token, hostLabel)

    fun parseReactionRace(content: String): Pair<String, String>? =
        com.maodouchat.group.play.GroupPkPolicy.parseReactionRace(content)

    fun spinWheel(): String = spinOptions.random()

    fun formatSpin(result: String, hostLabel: String): String {
        return "${GroupPlayPolicy.SPIN_PREFIX}${GroupPlayFieldEscape.esc(result.take(40))}|${hostLabel} spun the wheel"
    }

    fun parseSpin(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SPIN_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SPIN_PREFIX).substringBefore('|')).ifBlank { null }
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

    fun formatCoinFlip(side: String, hostLabel: String): String {
        val s = if (side.equals("HEADS", true)) "HEADS" else "TAILS"
        return "${GroupPlayPolicy.COINFLIP_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} flipped $s"
    }

    fun parseCoinFlip(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.COINFLIP_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.COINFLIP_PREFIX).substringBefore('|'))
    }

    fun formatSimon(seq: String, hostLabel: String): String {
        val s = seq.take(16)
        return "${GroupPlayPolicy.SIMON_PREFIX}${GroupPlayFieldEscape.esc(s)}|${hostLabel} simon says — repeat the sequence!"
    }

    fun parseSimon(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.SIMON_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.SIMON_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun randomEmojiDuel(): String = duelEmojis.random()

    fun formatEmojiDuel(pair: String, hostLabel: String): String {
        val p = pair.trim().ifBlank { randomEmojiDuel() }.take(16)
        return "${GroupPlayPolicy.EMOJI_DUEL_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} emoji duel — pick a side!"
    }

    fun parseEmojiDuel(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.EMOJI_DUEL_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.EMOJI_DUEL_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatHotPotato(seconds: Int, hostLabel: String): String {
        val s = seconds.coerceIn(5, 30)
        return "${GroupPlayPolicy.HOTPOTATO_PREFIX}$s|${hostLabel} hot potato — pass in ${s}s!"
    }

    fun parseHotPotato(content: String): Int? {
        if (!content.startsWith(GroupPlayPolicy.HOTPOTATO_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.HOTPOTATO_PREFIX).substringBefore('|')).toIntOrNull()
    }

    fun formatCountdownRace(seconds: Int, hostLabel: String): String {
        val s = seconds.coerceIn(2, 30)
        return "${GroupPlayPolicy.COUNTDOWN_RACE_PREFIX}$s|${hostLabel} countdown race - first reply wins!"
    }

    fun parseCountdownRace(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.COUNTDOWN_RACE_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.COUNTDOWN_RACE_PREFIX).substringBefore('|')).ifBlank { null }
    }

    fun formatReactionDuel(pair: String, hostLabel: String): String {
        val p = pair.trim().take(20)
        return "${GroupPlayPolicy.REACTION_DUEL_PREFIX}${GroupPlayFieldEscape.esc(p)}|${hostLabel} reaction duel"
    }

    fun parseReactionDuel(content: String): String? {
        if (!content.startsWith(GroupPlayPolicy.REACTION_DUEL_PREFIX)) return null
        return GroupPlayFieldEscape.unesc(content.removePrefix(GroupPlayPolicy.REACTION_DUEL_PREFIX).substringBefore('|')).ifBlank { null }
    }
}
