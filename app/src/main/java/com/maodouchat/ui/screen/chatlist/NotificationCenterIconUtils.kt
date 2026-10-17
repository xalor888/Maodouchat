package com.maodouchat.ui.screen.chatlist

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PhoneMissed
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChecklistRtl
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.maodouchat.data.repository.NotificationCenterItem
import com.maodouchat.navigation.AppLinkDestination
import com.maodouchat.navigation.AppLinkRouter
import com.maodouchat.notification.NotificationCenterType
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed

/** extra.chatId → deeplink → mergeKey（msg_/ai_tasks_） */
internal fun resolvedNotificationChatId(item: NotificationCenterItem): String? {
    item.extra["chatId"]?.takeIf { it.isNotBlank() }?.let { return it }
    when (val dest = AppLinkRouter.parseLegacyCenterDeeplink(item.deeplink.orEmpty())) {
        is AppLinkDestination.ChatDetail -> return dest.chatId
        is AppLinkDestination.AiTasksChat -> return dest.chatId
        else -> Unit
    }
    if (item.mergeKey.startsWith("msg_") && item.mergeKey.length > 4) {
        return item.mergeKey.removePrefix("msg_").takeIf { it.isNotBlank() }
    }
    if (item.mergeKey.startsWith("ai_tasks_") && item.mergeKey.length > 9) {
        return item.mergeKey.removePrefix("ai_tasks_").takeIf { it.isNotBlank() }
    }
    return null
}


internal fun iconForType(item: NotificationCenterItem): Pair<ImageVector, Color> {
    // 非 Composable：不能读 MaterialTheme.colorScheme。图标色用常量。
    if (item.type == NotificationCenterType.POST_INTERACTION) {
        val kind = item.extra["kind"].orEmpty()
        return if (kind == "comment" || kind == "comment_like") {
            Icons.Outlined.ChatBubbleOutline to Primary
        } else {
            Icons.Outlined.Favorite to Color(0xFFE91E63)
        }
    }
    return when (item.type) {
        NotificationCenterType.MISSED_CALL -> Icons.AutoMirrored.Outlined.PhoneMissed to UnreadRed
        NotificationCenterType.AI_TASK -> Icons.Outlined.ChecklistRtl to Primary
        NotificationCenterType.POST_INTERACTION -> Icons.Outlined.Favorite to Color(0xFFE91E63)
        NotificationCenterType.GROUP_INVITE -> Icons.Outlined.Public to Primary
        NotificationCenterType.SECURITY -> Icons.Outlined.VerifiedUser to Primary
        NotificationCenterType.MESSAGE -> Icons.Outlined.MarkChatUnread to Primary
        NotificationCenterType.FRIEND_REQUEST -> Icons.Outlined.PersonAdd to Primary
        else -> Icons.Outlined.Campaign to TextSecondary
    }
}
