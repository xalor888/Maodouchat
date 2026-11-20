package com.maodouchat.domain.messaging

import org.json.JSONObject

// TOTP 响应 JSON 解析簇：启用状态、开通响应与恢复码提取。
object TotpResponsePolicy {

    fun parseTotpEnabled(body: String): Boolean =
        runCatching { JSONObject(body).optBoolean("enabled", false) }.getOrDefault(false)

    // 开通响应解析：secret 缺失即抛错；恢复码按开通接口的逐项 getString 原样提取。
    fun parseTotpSetupResponse(rawJson: String): TotpState.SetupReady {
        val obj = JSONObject(rawJson)
        val secret = obj.optString("secret").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing secret in TOTP response")
        val otpauthUrl = obj.optString("otpauthUrl").takeIf { it.isNotBlank() }
        val backupCodesList = mutableListOf<String>()
        val backupArray = obj.optJSONArray("backupCodes")
        if (backupArray != null) {
            for (i in 0 until backupArray.length()) {
                backupCodesList.add(backupArray.getString(i))
            }
        }
        return TotpState.SetupReady(
            secret = secret,
            otpauthUrl = otpauthUrl,
            recoveryCodes = backupCodesList
        )
    }

    // 恢复码提取（确认/重新生成接口共用）：损坏 JSON 或缺 key 返回空列表，空串过滤。
    fun parseBackupCodes(body: String): List<String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
        val codes = obj?.optJSONArray("backupCodes") ?: return emptyList()
        return (0 until codes.length()).map { codes.optString(it) }.filter { it.isNotBlank() }
    }
}
