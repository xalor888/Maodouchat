package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.ServerIdentity

/**
 * 9.208：第三方服务器公告展示策略。
 *
 * 运营方通过 SERVER_ANNOUNCEMENT 或 STORAGE_DIR/server-announcement.txt 发布公告，
 * 第三方模式下用户进入主界面时弹一次；同一公告内容只弹一次（按内容哈希记录），
 * 公告更新后再次弹出。官方默认服务器不弹。
 */
object ServerAnnouncementNotice {
    private const val PREFS = "server_announcement_notice"
    private const val KEY_SHOWN_HASH = "shown_announcement_hash"

    /** 返回需要展示的公告文本；无需展示返回 null。 */
    fun pendingAnnouncement(context: Context): String? {
        if (!ServerIdentity.isThirdPartyServer) return null
        val info = ServerIdentity.current.value ?: return null
        val announcement = info.announcement.trim()
        if (announcement.isBlank()) return null
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shownHash = prefs.getString(KEY_SHOWN_HASH, null)
        return if (shownHash == hashOf(announcement)) null else announcement
    }

    fun markShown(context: Context, announcement: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SHOWN_HASH, hashOf(announcement))
            .apply()
    }

    private fun hashOf(text: String): String =
        Integer.toHexString(text.hashCode()) + ":" + text.length
}
