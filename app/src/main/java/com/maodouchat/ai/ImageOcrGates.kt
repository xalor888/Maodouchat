package com.maodouchat.ai

import android.content.Context
import com.maodouchat.util.RuntimeFlags

// 自动 OCR 的轮次门禁：开关前置条件与后台会话门闩。
object ImageOcrGates {
    fun preconditionsMet(context: Context): Boolean {
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.AI_IMAGE_OCR)) return false
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.AI_MASTER)) return false
        if (!AiPrivacyPreferences.mayUploadCloudContext(context)) return false
        if (!ImageOcrPreferences.isEnabled(context)) return false
        return true
    }

    // 后台会话门闩：登出/换号后本轮立即中止，防止旧账号图片密文继续上传。
    fun sessionGate(expectedUserId: String): Boolean =
        com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = expectedUserId)
}
