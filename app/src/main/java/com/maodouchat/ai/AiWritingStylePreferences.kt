package com.maodouchat.ai

import android.content.Context

/**
 * 账号隔离的写作风格偏好存储。默认全关；换号不串数据。
 */
// 写作风格偏好门面：实现已按簇拆到 AiWritingStylePrefsKeys（键与账号）、
// AiWritingStylePrefsReads（读取）、AiWritingStylePrefsWrites（写入），
// 这里只保留统一入口，全部透传，行为不变。
object AiWritingStylePreferences {
    fun snapshot(context: Context): AiWritingStylePolicy.Snapshot =
        AiWritingStylePrefsReads.snapshot(context)

    fun setEnabled(context: Context, enabled: Boolean) =
        AiWritingStylePrefsWrites.setEnabled(context, enabled)

    fun setPreset(context: Context, presetId: String) =
        AiWritingStylePrefsWrites.setPreset(context, presetId)

    fun setCustomNote(context: Context, note: String) =
        AiWritingStylePrefsWrites.setCustomNote(context, note)

    fun save(context: Context, enabled: Boolean, presetId: String?, customNote: String?) =
        AiWritingStylePrefsWrites.save(context, enabled, presetId, customNote)

    fun clear(context: Context) =
        AiWritingStylePrefsWrites.clear(context)
}
