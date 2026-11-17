package com.maodouchat.domain.messaging

// 密聊 SIM 变更防护：SIM 标识归一化与是否发生变更的判定。
object SecretSimChangePolicy {
    fun normalizeStoredSimId(raw: String?): String? = raw?.takeIf { it.isNotBlank() }

    fun normalizeSimId(simId: String): String = simId.trim()

    fun isSimChanged(previousSimId: String?, newSimId: String): Boolean =
        previousSimId != null && previousSimId != normalizeSimId(newSimId)
}
