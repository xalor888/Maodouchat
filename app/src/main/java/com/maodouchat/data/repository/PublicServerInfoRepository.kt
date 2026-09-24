package com.maodouchat.data.repository

import com.maodouchat.network.ApiService
import com.maodouchat.network.PublicUpdatesDto

/**
 * 免鉴权的服务端公共信息（G328c）。
 *
 * 为什么要有它：`/api/public/status` 与 `/api/public/updates` 原先由
 * `ui/` 直接调 `ApiService`（横幅、关于页的更新检查）。按分层，**传输层调用属于 data 层**；
 * `ui/` 只该依赖 repository。这条边界此前有一张「只许降」的棘轮在管
 * （`ClientArchitectureTest.frozenUiApiCallers`），本仓库是它的第一个真实下降。
 *
 * 注意：它刻意保持很薄——不做缓存、不做重试。调用方（横幅/更新检查）本来就有自己的
 * 容错与节流，加一层策略只会让「谁负责节流」变得模糊。
 */
internal class PublicServerInfoRepository(
    private val fetchStatus: suspend () -> Result<String> = { ApiService.getPublicStatus() },
    private val fetchUpdates: suspend (String) -> Result<PublicUpdatesDto> = { baseUrl -> ApiService.getPublicUpdates(baseUrl) },
) {
    /** 服务器公共状态（未登录也可读）；失败返回 null，调用方按「没有横幅」处理。 */
    suspend fun publicStatus(): String? = fetchStatus().getOrNull()?.takeIf(String::isNotBlank)

    /** 公有更新信息（关于页的「检查更新」）。 */
    suspend fun publicUpdates(baseUrl: String): Result<PublicUpdatesDto> = fetchUpdates(baseUrl)
}
