package com.maodouchat.group.play

/**
 * 群接龙玩法策略（U06 解耦）。
 */
object GroupChainPolicy {
    const val WORD_PREFIX = "WORD:"

    val wordChainSeeds = listOf(
        "apple", "banana", "cat", "dog", "elephant", "fish", "grape", "hat", "ice",
        "juice", "kite", "lemon", "monkey", "nut", "orange", "pear", "queen", "rabbit",
        "sun", "tiger", "umbrella", "van", "water", "x-ray", "yellow", "zebra"
    )

    fun randomWordSeed(): String = wordChainSeeds.random()

    fun formatWordChain(seed: String, userLabel: String): String {
        return "${WORD_PREFIX}${seed}|${userLabel} word chain: start with '$seed'"
    }

    fun parseWordChain(content: String): Pair<String, String>? {
        if (!content.startsWith(WORD_PREFIX)) return null
        val body = content.removePrefix(WORD_PREFIX)
        val seed = body.substringBefore('|').trim()
        val rest = body.substringAfter('|', "")
        if (seed.isBlank()) return null
        return seed to rest
    }
}
