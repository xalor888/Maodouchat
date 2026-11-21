package com.maodouchat.ui.screen.chatlist

import com.maodouchat.util.RuntimeFlags
import org.json.JSONObject
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.maodouchat.data.repository.PublicServerInfoRepository
import com.maodouchat.domain.messaging.parseServerFeatureFlags
import com.maodouchat.domain.conversation.buildPublicStatusBanner

/**
 * 拉取 `/api/public/status` 并把公屏状态落到本地（G144 从 `ChatListScreen.kt` 的
 * `LaunchedEffect(Unit)` 拆出，原 53 行）。
 *
 * 一次调用做四件事：
 * 1. `buildPublicStatusBanner` 拼出公屏横幅文本（维护文案优先，否则各段用 " · " 连接）；
 * 2. `parseServerFeatureFlags` + `applyServerFeatureFlags` 写入 86 个功能开关，
 *    以及 `pushHmacKey`、AI 总闸（`aiEnabled`）、`secretSurfaceFlags` 八项密聊默认值；
 * 3. 返回该横幅（null 表示不显示）。
 *
 * **纯搬移，不改判断**：每条默认值、每个键名、优先级顺序都与原先一致。
 */
internal suspend fun fetchPublicStatusBanner(context: android.content.Context): String? =
    withContext(Dispatchers.IO) {
        // G328c：传输层调用移到 data 层（PublicServerInfoRepository），ui 只依赖 repository。
        val raw = PublicServerInfoRepository().publicStatus().orEmpty()
        if (raw.isBlank()) return@withContext null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
        val flags = parseServerFeatureFlags(o)
        // optString 会把显式 JSON null 读成字面 "null"，先排除（与横幅拼装同法）
        val pushHmacKey = o.optString("pushHmacKey").takeIf { it != "null" }.orEmpty().ifBlank { null }

        // G143：86 行开关写入收敛成一次调用（绑定表见 :domain:messaging 的 serverFeatureFlagBindings）
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
        buildPublicStatusBanner(o)    }
