package com.maodouchat.core.session

/** 账号世代（A02）：换号/切服/删号时递增，用于隔离后台任务与本地行。 */
@JvmInline
value class AccountGeneration(val value: Long) {
    fun isNewerThan(other: AccountGeneration): Boolean = value > other.value

    companion object {
        val INITIAL = AccountGeneration(0L)
    }
}
