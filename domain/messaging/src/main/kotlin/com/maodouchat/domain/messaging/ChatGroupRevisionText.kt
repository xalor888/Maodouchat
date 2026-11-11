package com.maodouchat.domain.messaging

/** 群修订处理向 UI 写入的文案种类：domain 侧只定种类，本地化字符串由 app 侧映射提供。 */
enum class ChatGroupRevisionText {
    LEFT_GROUP_KEY_CLEARED,
    GROUP_MEMBERS_CHANGED_KEY,
    GROUP_NAME_UPDATED,
    GROUP_ROLE_UPDATED,
    GROUP_TITLE_UPDATED,
    GROUP_NICKNAME_UPDATED,
    GROUP_ANNOUNCEMENT_UPDATED,
    GROUP_MUTE_UPDATED,
    GROUP_INFO_UPDATED,
}
