package com.maodouchat.util

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.VoicePlayedEntity
import com.maodouchat.network.TokenManager

/**
 * 1.176：语音消息「已播放」标记（账号隔离；用于气泡未读红点）。
 * Room 表承载（退役 `voice_played` SharedPreferences 存储）；调用方签名不变。
 */
object VoicePlayedStore {

    /** 指定语音消息是否已播放过。 */
    fun isPlayed(context: Context, messageId: String): Boolean {
        if (messageId.isBlank()) return true
        val userId = userId(context) ?: return false
        return runCatching {
            db(context).voicePlayedDao().isPlayedBlocking(userId, messageId) > 0
        }.getOrDefault(false)
    }

    /** 标记语音消息已播放（自然播放完成时调用）。 */
    fun markPlayed(context: Context, messageId: String) {
        if (messageId.isBlank()) return
        val userId = userId(context) ?: return
        runCatching {
            db(context).voicePlayedDao().markPlayedBlocking(
                VoicePlayedEntity(
                    ownerUserId = userId,
                    messageId = messageId,
                    playedAtMillis = System.currentTimeMillis(),
                )
            )
        }
    }

    private fun db(ctx: Context): AppDatabase =
        AppDatabase.getInstance(ctx.applicationContext)

    private fun userId(ctx: Context): String? =
        TokenManager.getInstance(ctx.applicationContext).getUserId()?.takeIf(String::isNotBlank)
}
