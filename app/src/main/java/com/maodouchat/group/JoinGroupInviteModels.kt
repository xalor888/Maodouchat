package com.maodouchat.group

import com.maodouchat.network.ChatDto
import com.maodouchat.contacts.Feedback

/**
 * 群邀请加入用例（P08）：深链 / QR / 系统入口统一经此入口调用 join-by-invite。
 * UI 只观察 [JoinGroupInviteResult]，不直连 ApiService。
 */
sealed interface JoinGroupInviteResult {
    data class Joined(val chat: ChatDto) : JoinGroupInviteResult
    data object InvalidCode : JoinGroupInviteResult
    data class Failed(val feedback: Feedback) : JoinGroupInviteResult
    /** 加入成功后账号世代/会话已变，丢弃结果不导航。 */
    data object Aborted : JoinGroupInviteResult
}
