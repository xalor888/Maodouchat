package com.maodouchat.group.play

import com.maodouchat.util.rpsChoices
import kotlin.random.Random

/**
 * 群 PK 玩法策略（U06 解耦）。
 * 包含剪刀石头布、数字炸弹、手速抢答等 PK 模式。
 */
object GroupPkPolicy {
    const val RPS_PREFIX = "RPS:"
    const val BOMB_PREFIX = "BOMB:"
    const val RACE_PREFIX = "RACE:"

    private fun esc(s: String): String = s.replace("|", "\u0001").replace("^", "\u0002")
    private fun unesc(s: String): String = s.replace("\u0001", "|").replace("\u0002", "^")

    // G154c：猜拳选项原先在这里有一份成员副本，与 GroupPlayData.rpsChoices 重复；
    // 改一处忘了另一处，客户端显示和编解码判定就会不一致。
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

    fun rollNumberBomb(max: Int = 100): Pair<Int, Int> {
        val hi = max.coerceIn(10, 1000)
        val secret = Random.nextInt(1, hi + 1)
        return secret to hi
    }

    fun formatNumberBomb(secret: Int, max: Int, hostLabel: String): String {
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

    val raceTokens = listOf("🚀", "⚡", "🔥", "🎯", "💎", "⭐", "🏆", "🐱")
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
}
