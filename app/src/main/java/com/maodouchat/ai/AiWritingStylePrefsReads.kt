package com.maodouchat.ai

import android.content.Context
import com.maodouchat.ai.AiWritingStylePolicy
import com.maodouchat.ai.AiWritingStylePrefsKeys.KEY_CUSTOM
import com.maodouchat.ai.AiWritingStylePrefsKeys.KEY_ENABLED
import com.maodouchat.ai.AiWritingStylePrefsKeys.KEY_PRESET
import com.maodouchat.ai.AiWritingStylePrefsKeys.account
import com.maodouchat.ai.AiWritingStylePrefsKeys.key
import com.maodouchat.ai.AiWritingStylePrefsKeys.prefs

// 读取簇：读 prefs 组装 Snapshot。原 AiWritingStylePreferences.snapshot 逐字搬入。
internal object AiWritingStylePrefsReads {
    fun snapshot(context: Context): AiWritingStylePolicy.Snapshot {
        val account = account(context) ?: return AiWritingStylePolicy.clear()
        val prefs = prefs(context)
        return AiWritingStylePolicy.normalize(
            enabled = prefs.getBoolean(key(KEY_ENABLED, account), false),
            presetId = prefs.getString(key(KEY_PRESET, account), null),
            customNote = prefs.getString(key(KEY_CUSTOM, account), null)
        )
    }
}
