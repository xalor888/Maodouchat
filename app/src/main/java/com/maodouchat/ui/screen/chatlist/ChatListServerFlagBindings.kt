package com.maodouchat.ui.screen.chatlist

import com.maodouchat.domain.messaging.serverFeatureFlagBindings
import com.maodouchat.util.RuntimeFlags

/**
 * 把服务端下发的功能开关一次性写进 `RuntimeFlags`（G143）。
 *
 * 绑定表已迁入 `:domain:messaging`（`serverFeatureFlagBindings`，与
 * `parseServerFeatureFlags` 同处一模块）；此处只留需 `Context` 的写入，逐对语义不变。
 */
internal fun applyServerFeatureFlags(
    context: android.content.Context,
    flags: Map<String, Boolean>,
) {
    serverFeatureFlagBindings.forEach { (feature, key) ->
        flags[key]?.let { RuntimeFlags.setEnabled(context, feature, it) }
    }
}
