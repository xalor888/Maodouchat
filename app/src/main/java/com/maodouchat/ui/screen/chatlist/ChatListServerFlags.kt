package com.maodouchat.ui.screen.chatlist

import com.maodouchat.util.RuntimeFlags
import org.json.JSONObject
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.maodouchat.data.repository.PublicServerInfoRepository
import com.maodouchat.domain.messaging.parseServerFeatureFlags

/**
 * 拉取 `/api/public/status` 并把公屏状态落到本地（G144 从 `ChatListScreen.kt` 的
 * `LaunchedEffect(Unit)` 拆出，原 53 行）。
 *
 * 一次调用做四件事：
 * 1. `safeOpt` 取横幅 / E2EE 横幅 / 公告 / 维护文案 / 最低版本——`safeOpt` 排除
 *    `optString` 在键缺失时返回的字面 `"null"`，避免横幅显示 "null" 或写入垃圾 key；
 * 2. `parseServerFeatureFlags` + `applyServerFeatureFlags` 写入 86 个功能开关，
 *    以及 `pushHmacKey`、AI 总闸（`aiEnabled`）、`secretSurfaceFlags` 八项密聊默认值；
 * 3. 拼出公屏横幅文本（维护文案优先，否则各段用 " · " 连接）；
 * 4. 返回该横幅（null 表示不显示）。
 *
 * **键名兼容**：服务端下发 `maintenance`，旧版本曾用 `maintenanceMode`——
 * 两条都试，保持老服务端可用。
 *
 * **纯搬移，不改判断**：每条默认值、每个键名、优先级顺序都与原先一致。
 */
internal suspend fun fetchPublicStatusBanner(context: android.content.Context): String? =
    withContext(Dispatchers.IO) {
        // G328c：传输层调用移到 data 层（PublicServerInfoRepository），ui 只依赖 repository。
        val raw = PublicServerInfoRepository().publicStatus().orEmpty()
        if (raw.isBlank()) return@withContext null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
        // optString 缺失键返回字面 "null"（非 blank）——统一用 safeOpt 排除，避免横幅显示 "null"/写入垃圾 key
        fun safeOpt(key: String): String = if (o.has(key)) o.optString(key).takeIf { it != "null" }.orEmpty() else ""
        val banner = safeOpt("banner").ifBlank { safeOpt("globalBanner") }
        val e2eeBanner = safeOpt("forceE2eeBanner").ifBlank { safeOpt("e2eeBanner") }
        val announcement = safeOpt("publicAnnouncement").ifBlank { safeOpt("announcement") }
        val maintMsg = safeOpt("maintenanceMessage")
        // 兼容键名：服务端下发 "maintenance"；旧版本曾用 "maintenanceMode"
        val publicMaintenance = if (o.has("maintenance")) o.optBoolean("maintenance", false) else o.optBoolean("maintenanceMode", false)
        val minApp = safeOpt("minAppVersion")
        val pqxdh = o.optBoolean("pqxdhPreview", false)

        val flags = parseServerFeatureFlags(o)
        val pushHmacKey = safeOpt("pushHmacKey").ifBlank { null }

        // G143：86 行开关写入收敛成一次调用（绑定表见 ChatListServerFlags.FLAG_BINDINGS）
        applyServerFeatureFlags(context, flags)
        pushHmacKey?.let { com.maodouchat.util.PushVerifyPrefs.setKey(context, it) }

        // 服务端 AI 总开关 → 本地 AI_MASTER（false 时折叠全部 AI 入口）
        if (o.has("aiEnabled")) RuntimeFlags.setEnabled(context, RuntimeFlags.AI_MASTER, o.optBoolean("aiEnabled", true))
        // B2 密聊防泄漏扩展（surface #71–#78）：服务端开关 → SecretXxxPrefs。
        // 仅当用户从未在设置页显式设置过时接受服务端默认值，本地开关永远优先
        //（设置页声明"仅本机生效"；无条件覆盖会让用户"关了又开"）。
        val ssf = o.optJSONObject("secretSurfaceFlags")
        if (ssf != null) {
            com.maodouchat.util.SecretScreenshotBurnPrefs.applyServerDefault(context, ssf.optBoolean("secretScreenshotBurnEnabled", true))
            com.maodouchat.util.SecretAutoDestroyPrefs.applyServerDefault(context, ssf.optBoolean("secretAutoDestroyEnabled", true))
            com.maodouchat.util.SecretForwardWhitelistPrefs.applyServerDefault(context, ssf.optBoolean("secretForwardWhitelistEnabled", true))
            com.maodouchat.util.SecretSimChangePrefs.applyServerDefault(context, ssf.optBoolean("secretSimChangeProtectionEnabled", true))
            com.maodouchat.util.Secret2faGatePrefs.applyServerDefault(context, ssf.optBoolean("secret2faGateEnabled", true))
            com.maodouchat.util.SecretNewDeviceRiskPrefs.applyServerDefault(context, ssf.optBoolean("secretNewDeviceRiskEnabled", true))
            com.maodouchat.util.SecretDeviceVerifyPrefs.applyServerDefault(context, ssf.optBoolean("secretDeviceVerifyEnabled", true))
            com.maodouchat.util.SecretSessionNoticePrefs.applyServerDefault(context, ssf.optBoolean("secretSessionNoticeEnabled", true))
        }
        val upgradeHint = if (minApp.isNotBlank() && minApp != "0") "Min version: $minApp" else null
        val pqxdhHint = if (pqxdh) "PQXDH preview on" else null
        val parts = listOfNotNull(
            banner.takeIf { it.isNotBlank() },
            e2eeBanner.takeIf { it.isNotBlank() },
            announcement.takeIf { it.isNotBlank() },
            upgradeHint,
            pqxdhHint
        )
        when {
            publicMaintenance && maintMsg.isNotBlank() -> maintMsg
            parts.isNotEmpty() -> parts.joinToString(" · ")
            else -> null
        }    }
