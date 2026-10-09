package com.maodouchat.ai

import android.content.Context
import com.maodouchat.util.RuntimeFlags

// 会话画像门禁：AI 处理同意 + 本地画像总开关。
internal object AiProfileGates {
    /** 本地画像开关（与 AI 处理同意独立，默认开，纯本机）。 */
    fun isAllowed(context: Context): Boolean =
        AiPrivacyPreferences.mayUploadCloudContext(context) && isLocalProfileEnabled(context)

    fun isLocalProfileEnabled(context: Context): Boolean =
        RuntimeFlags.isEnabled(context, RuntimeFlags.AI_MASTER)
}
