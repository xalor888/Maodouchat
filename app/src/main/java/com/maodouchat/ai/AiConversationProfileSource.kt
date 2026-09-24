package com.maodouchat.ai

import com.maodouchat.ai.AiConversationProfile

/**
 * 「会话画像」AI 能力的读取端口（G73）。
 *
 * **为什么需要它**：`ChatDetailRoute.kt` 里的 `LaunchedEffect` 直接写了
 * `(appContext as MaodouchatApp).database` 再调 `AiConversationProfile.build(...)`——
 * UI 层因此认识了应用单例与数据库类型。`ClientArchitectureTest` 的
 * `frozenUiAppDatabaseGrabbers` 名单正是为拦这个而存在的。
 *
 * 端口只暴露 UI 真正需要的那一个动作；`Context` / `AppDatabase` 由装配点
 * （data 层的实现类）持有，Route 从此只看见 `chatId` 与结果。
 */
interface AiConversationProfileSource {
    suspend fun build(chatId: String): AiConversationProfile.ConversationProfile
}
