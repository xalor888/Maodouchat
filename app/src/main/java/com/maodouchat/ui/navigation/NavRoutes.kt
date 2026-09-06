package com.maodouchat.ui.navigation

import android.net.Uri

/**
 * 路由定义（P08：自 `NavGraph.kt` 迁出，注册只剩 composable 装配）。
 *
 * 本文件只放路由模式串与 builder；各 feature 的 destination contract
 * 在此基础上按域分组演进（后续步骤）。
 */
object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val CHAT_DETAIL = "chat_detail/{chatId}?messageId={messageId}"
    const val GROUP_DETAIL = "group_detail/{chatId}"
    const val GROUP_EDIT = "group_detail/{chatId}/edit"
    const val GROUP_INVITE = "group_detail/{chatId}/invite"
    const val STARRED_MESSAGES = "starred_messages?chatId={chatId}"
    const val AI_TASKS = "ai_tasks/{chatId}"
    const val MEDIA_CENTER = "media_center/{chatId}"
    const val CALL = "call/{contactId}/{contactName}/{callType}"
    const val INCOMING_CALL = "incoming_call"
    const val CALL_HISTORY = "call_history"
    const val SETTINGS_ACCOUNT_SECURITY = "settings/account_security"
    const val SETTINGS_MY_REPORTS = "settings/my_reports"
    const val SETTINGS_BLOCKED_USERS = "settings/blocked_users"
    const val SETTINGS_NOTIFICATIONS = "settings/notifications"
    const val SETTINGS_AI_PRIVACY = "settings/ai_privacy"
    const val AGENT = "agent"
    const val SETTINGS_MODERATION = "settings/moderation"
    const val SETTINGS_GENERAL = "settings/general"
    const val SETTINGS_ABOUT = "settings/about"
    // 9.253：主题编辑器（TG 式高自定义 + .attheme 导入导出）
    const val SETTINGS_THEME_EDITOR = "settings/theme_editor"
    const val SETTINGS_THEME_WORKBENCH = "settings/theme_workbench"
    const val SETTINGS_SERVER = "settings/server"
    const val WATERMARK_FORENSIC = "watermark_forensic"
    const val DEVELOPER_BOTS = "developer_bots"
    const val MY_QR_CODE = "my_qr_code"
    const val SCAN = "scan"
    const val NEARBY = "nearby"
    const val MOMENTS = "moments"
    const val AUTHOR_PROFILE = "author/{authorId}"
    const val POST_DETAIL = "post/{postId}?comment={comment}"
    const val GLOBAL_SEARCH = "global_search"
    const val NOTIFICATION_CENTER = "notification_center"
    const val PUBLIC_PROFILE = "public_profile/{username}"
    // 群玩法 B3：群投票 / 群签到+排行 / 群接龙 / 群 PK
    const val GROUP_POLL = "group_poll/{chatId}"
    const val GROUP_CHECKIN = "group_checkin/{chatId}"
    const val GROUP_CHAIN = "group_chain/{chatId}"
    const val GROUP_PK = "group_pk/{chatId}"
    // B5 新增（仅追加）：平板双栏布局路由（列表左栏 / 聊天详情右栏）
    const val CHAT_DETAIL_LIST_PANE = "chat_detail_list_pane"
    const val CHAT_DETAIL_TWO_PANE = "chat_detail_two_pane/{chatId}"

    fun authorProfile(authorId: String) = "author/${Uri.encode(authorId)}"
    // 1.132：可带 comment 查询参数（通知跳转定位到具体评论）
    fun postDetail(postId: String, commentId: String? = null) =
        "post/${Uri.encode(postId)}?comment=${Uri.encode(commentId.orEmpty())}"
    fun publicProfile(username: String) = "public_profile/${Uri.encode(username)}"
    fun groupPoll(chatId: String) = "group_poll/${Uri.encode(chatId)}"
    fun groupCheckin(chatId: String) = "group_checkin/${Uri.encode(chatId)}"
    fun groupChain(chatId: String) = "group_chain/${Uri.encode(chatId)}"
    fun groupPk(chatId: String) = "group_pk/${Uri.encode(chatId)}"
    // B5 新增（仅追加）：双栏「详情」路由 builder（嵌套 NavHost 内使用）
    fun chatDetailTwoPane(chatId: String) = "chat_detail_two_pane/${Uri.encode(chatId)}"

    fun chatDetail(chatId: String, messageId: String? = null): String {
        val base = "chat_detail/${Uri.encode(chatId)}"
        return messageId?.takeIf(String::isNotBlank)?.let { "$base?messageId=${Uri.encode(it)}" } ?: base
    }
    fun groupDetail(chatId: String) = "group_detail/${Uri.encode(chatId)}"
    fun groupEdit(chatId: String) = "group_detail/${Uri.encode(chatId)}/edit"
    fun groupInvite(chatId: String) = "group_detail/${Uri.encode(chatId)}/invite"
    fun starredMessages(chatId: String? = null): String {
        val id = chatId?.takeIf { it.isNotBlank() }?.let { Uri.encode(it) }.orEmpty()
        return if (id.isEmpty()) "starred_messages" else "starred_messages?chatId=$id"
    }
    fun aiTasks(chatId: String) = "ai_tasks/${Uri.encode(chatId)}"
    fun mediaCenter(chatId: String) = "media_center/${Uri.encode(chatId)}"
    fun call(contactId: String, contactName: String, callType: String = "AUDIO") =
        "call/${Uri.encode(contactId)}/${Uri.encode(contactName)}/${Uri.encode(callType)}"
    fun incomingCall() = INCOMING_CALL
}

/**
 * 底部导航 Tab 索引
 */
object MainTab {
    const val CHATS = 0
    const val CONTACTS = 1
    const val EXPLORE = 2
    const val SETTINGS = 3
}
