package com.maodouchat.ai

import android.content.Context
import androidx.core.content.edit
import com.maodouchat.ai.AiWritingStylePolicy
import com.maodouchat.ai.AiWritingStylePrefsKeys.KEY_CUSTOM
import com.maodouchat.ai.AiWritingStylePrefsKeys.KEY_ENABLED
import com.maodouchat.ai.AiWritingStylePrefsKeys.KEY_PRESET
import com.maodouchat.ai.AiWritingStylePrefsKeys.account
import com.maodouchat.ai.AiWritingStylePrefsKeys.key
import com.maodouchat.ai.AiWritingStylePrefsKeys.prefs

// 写入簇：风格偏好的增删改写。原 AiWritingStylePreferences 写入函数逐字搬入。
internal object AiWritingStylePrefsWrites {
    fun setEnabled(context: Context, enabled: Boolean) {
        val account = account(context) ?: return
        if (!enabled) {
            clear(context)
            return
        }
        prefs(context).edit { putBoolean(key(KEY_ENABLED, account), true) }
    }

    fun setPreset(context: Context, presetId: String) {
        val account = account(context) ?: return
        val preset = AiWritingStylePolicy.Preset.fromId(presetId)
        prefs(context).edit {
            putString(key(KEY_PRESET, account), preset.id)
        }
    }

    fun setCustomNote(context: Context, note: String) {
        val account = account(context) ?: return
        val normalized = AiWritingStylePolicy.normalizeCustomNote(note)
        prefs(context).edit {
            putString(key(KEY_CUSTOM, account), normalized)
        }
    }

    fun save(context: Context, enabled: Boolean, presetId: String?, customNote: String?) {
        val account = account(context) ?: return
        val snap = AiWritingStylePolicy.normalize(enabled, presetId, customNote)
        val eKey = key(KEY_ENABLED, account)
        val pKey = key(KEY_PRESET, account)
        val cKey = key(KEY_CUSTOM, account)
        prefs(context).edit {
            if (!snap.enabled) {
                remove(eKey).remove(pKey).remove(cKey)
            } else {
                putBoolean(eKey, true)
                    .putString(pKey, snap.preset.id)
                    .putString(cKey, snap.customNote)
            }
        }
    }

    fun clear(context: Context) {
        val account = account(context) ?: return
        prefs(context).edit {
            remove(key(KEY_ENABLED, account))
            remove(key(KEY_PRESET, account))
            remove(key(KEY_CUSTOM, account))
        }
    }
}
