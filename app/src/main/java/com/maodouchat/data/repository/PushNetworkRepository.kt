package com.maodouchat.data.repository

import com.maodouchat.network.ApiService

/**
 * 推送校验密钥的**远端**读取（G328c）。
 *
 * `pushVerifyKey` 返回原始 JSON（含 key 与清空指令），解析与落盘属于调用方的策略
 * （`ChatListPorts` 里的 `applyPushVerifyKey`），仓库只负责取——职责切在这一刀上，
 * 是为了让「什么时候清、什么时候设」这条业务规则留在它本来所在的地方。
 */
internal class PushNetworkRepository(
    private val verifyKeyApi: suspend (String) -> Result<String> = { token -> ApiService.getPushVerifyKey(token) },
) {
    /** 服务端下发的最新推送校验密钥（原始 JSON）。 */
    suspend fun verifyKey(token: String): Result<String> = verifyKeyApi(token)
}
