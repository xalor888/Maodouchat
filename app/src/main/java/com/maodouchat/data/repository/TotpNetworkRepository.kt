package com.maodouchat.data.repository

import com.maodouchat.network.ApiService

/**
 * 两因子（TOTP）管理的**远端**调用（G328c）。
 *
 * 覆盖设置页「账号与安全」里那一组：查状态、开始绑定、确认绑定、关闭、重生成恢复码。
 * 与前几轮同样的理由——`ui/` 只该依赖 repository，传输层调用属于 data 层
 * （棘轮 `ClientArchitectureTest.frozenUiApiCallers`）。
 *
 * 单列一个仓库而不是并进账号安全那个：TOTP 这组是**同一事务语义**的五个动作
 * （状态机：未绑定 → 绑定中 → 已绑定 → 关闭），放在一起读得出来它们的顺序约束；
 * 混进「改名/删设备」那堆命令里就看不出来了。
 *
 * 刻意很薄（不缓存、不重试）：状态必须每次从服务端读——缓存它会让「刚在另一台设备
 * 关掉了 2FA」在本机看起来还开着。
 */
internal class TotpNetworkRepository(
    private val statusApi: suspend (String) -> Result<Boolean> = { token -> ApiService.totpStatus(token) },
    private val statusRawApi: suspend (String) -> Result<String> = { token -> ApiService.getTotpStatus(token) },
    private val setupApi: suspend (String) -> Result<String> = { token -> ApiService.setupTotp(token) },
    private val confirmApi: suspend (String, String) -> Result<List<String>> =
        { token, code -> ApiService.confirmTotp(token, code) },
    private val disableApi: suspend (String, String) -> Result<String> =
        { token, code -> ApiService.disableTotp(token, code) },
    private val regenerateApi: suspend (String, String) -> Result<List<String>> =
        { token, code -> ApiService.regenerateTotpCodes(token, code) },
) {
    /** 是否已启用（`getOrDefault(false)` 的调用点由调用方自己决定默认值）。 */
    suspend fun status(token: String): Result<Boolean> = statusApi(token)

    /**
     * 服务端**原始**状态体（历史接口 `getTotpStatus` 返回的是 JSON 文本，由调用方解析 `enabled`）。
     * 与 [status] 并存不是重复：两者对应服务端两个不同端点，返回形态也不同。
     */
    suspend fun statusRaw(token: String): Result<String> = statusRawApi(token)

    /** 开始绑定，返回 provisioning secret（供二维码/手动输入）。 */
    suspend fun setup(token: String): Result<String> = setupApi(token)

    /** 确认绑定，返回 8 个恢复码（明文仅此一次）。 */
    suspend fun confirm(token: String, code: String): Result<List<String>> = confirmApi(token, code)

    /** 关闭 2FA（需当前有效验证码）。 */
    suspend fun disable(token: String, code: String): Result<String> = disableApi(token, code)

    /** 重新生成恢复码（旧码全部作废）。 */
    suspend fun regenerateBackupCodes(token: String, code: String): Result<List<String>> =
        regenerateApi(token, code)
}
