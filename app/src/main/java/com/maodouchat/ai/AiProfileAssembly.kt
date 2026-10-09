package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase

// 会话画像装配门面：生成簇 + 缓存簇的统一入口，全部透传。
// 密聊会话不参与（结果不应落可搜索缓存）；明文只存在于本机 SQLCipher 解密通道。
internal object AiProfileAssembly {

    suspend fun build(
        context: Context,
        database: AppDatabase,
        chatId: String
    ): AiConversationProfile.ConversationProfile =
        AiProfileBuild.build(context, database, chatId)

    /** 读取上次画像缓存（仅本地，不发请求）。 */
    suspend fun cached(
        context: Context,
        database: AppDatabase,
        chatId: String
    ): AiConversationProfile.ConversationProfile? =
        AiProfileCached.cached(context, database, chatId)
}
