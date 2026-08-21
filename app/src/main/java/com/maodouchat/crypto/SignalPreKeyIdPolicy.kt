package com.maodouchat.crypto

/**
 * Signal PreKey / Signed PreKey ID 合法区间与分配。
 *
 * libsignal 与本服务端均要求 1..16_777_215（24-bit）。随机 ID 必须落在该闭区间，
 * 批次分配必须保证 [start, start+count) 不越界。
 */
object SignalPreKeyIdPolicy {
    const val MAX_ID = 16_777_215

    fun isValid(id: Int): Boolean = id in 1..MAX_ID

    /**
     * @param randomIntExclusive 传入上界 n，返回 [0, n)
     */
    fun randomSignedPreKeyId(randomIntExclusive: (Int) -> Int): Int =
        1 + randomIntExclusive(MAX_ID)

    /**
     * 下一批一次性 PreKey 的起始 ID。优先从 [maxExistingId]+1 连续分配；
     * 剩余空间不够一整批时回绕到 1..maxStart，避免 nextInt 非正或 ID 超 24-bit。
     */
    fun nextBatchStartId(
        maxExistingId: Int,
        count: Int,
        randomIntExclusive: (Int) -> Int,
    ): Int {
        val n = count.coerceAtLeast(1)
        val maxStart = (MAX_ID - n + 1).coerceAtLeast(1)
        val sequential = maxExistingId + 1
        return if (sequential in 1..maxStart) {
            sequential
        } else {
            1 + randomIntExclusive(maxStart)
        }
    }
}
