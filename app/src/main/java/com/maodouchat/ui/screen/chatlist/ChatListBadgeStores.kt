package com.maodouchat.ui.screen.chatlist

/** 1.54：底部导航「会话」未读角标总计数（ChatListViewModel 推送，BottomNavBar 订阅）。 */
object UnreadBadgeStore {
    val totalUnread = kotlinx.coroutines.flow.MutableStateFlow(0)
}

/** 1.112：底部导航「动态」未读互动角标（POST_INTERACTION 未读数，ChatListViewModel 推送）。 */
object ExploreBadgeStore {
    val count = kotlinx.coroutines.flow.MutableStateFlow(0)
}
