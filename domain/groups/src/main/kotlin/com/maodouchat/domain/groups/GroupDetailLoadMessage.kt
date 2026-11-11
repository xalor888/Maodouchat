package com.maodouchat.domain.groups

/**
 * 群详情加载/邀请加载向 UI 写入的文案种类。domain 侧只定种类，真正的本地化字符串由 app 侧
 * groupDetailLoadMessageResId 映射提供——domain 模块不引用 app 的 R。
 */
enum class GroupDetailLoadMessage {
    SESSION_EXPIRED,
    CHAT_GROUP,
    LOAD_FAILED,
    INVITE_REFRESHED,
    INVITE_FAILED,
}
