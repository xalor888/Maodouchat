package com.maodouchat.ui.screen.chatlist

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.repository.NotificationCenterItem
import com.maodouchat.notification.NotificationCenterType
import com.maodouchat.ui.component.SearchHighlightSurface

internal enum class NotifFilter {
    ALL,
    UNREAD,
    MESSAGE,
    MISSED_CALL,
    AI_TASK,
    POST_INTERACTION,
    FRIEND_REQUEST;

    fun matches(item: NotificationCenterItem): Boolean = when (this) {
        ALL -> true
        UNREAD -> !item.read
        MESSAGE -> item.type == NotificationCenterType.MESSAGE
        MISSED_CALL -> item.type == NotificationCenterType.MISSED_CALL
        AI_TASK -> item.type == NotificationCenterType.AI_TASK
        POST_INTERACTION -> item.type == NotificationCenterType.POST_INTERACTION
        FRIEND_REQUEST -> item.type == NotificationCenterType.FRIEND_REQUEST
    }
}

@Composable
internal fun NotificationFilterStrip(
    selected: NotifFilter,
    onSelect: (NotifFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = listOf(
        NotifFilter.ALL to stringResource(R.string.notif_center_filter_all),
        NotifFilter.UNREAD to stringResource(R.string.notif_center_filter_unread),
        NotifFilter.MESSAGE to stringResource(R.string.notif_center_subtitle_message),
        NotifFilter.MISSED_CALL to stringResource(R.string.notif_center_filter_calls),
        NotifFilter.AI_TASK to stringResource(R.string.notif_center_filter_ai),
        NotifFilter.POST_INTERACTION to stringResource(R.string.notif_center_filter_social),
        NotifFilter.FRIEND_REQUEST to stringResource(R.string.notif_center_filter_friends)
    )
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { (filter, label) ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected == filter,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
        }
    }
}

internal fun List<NotificationCenterItem>.groupByDay(): List<Pair<StringBucket, List<NotificationCenterItem>>> {
    val now = System.currentTimeMillis()
    val dayMs = 24L * 3600L * 1000L
    return this
        .groupBy { item ->
            // 8.48 修复 M5：未来时间戳（时钟超前/服务端未来时间）强制归 TODAY——
            // 此前负 diffDays 落入 YESTERDAY/WEEK，未来 8 天以上也归 WEEK
            val diffDays = ((now - item.updatedAt) / dayMs).coerceAtLeast(0L)
            when {
                diffDays == 0L -> StringBucket.TODAY
                diffDays == 1L -> StringBucket.YESTERDAY
                diffDays <= 7L -> StringBucket.WEEK
                else -> StringBucket.EARLIER
            }
        }
        .map { (bucket, list) ->
            val ordered = list.sortedByDescending { it.updatedAt }
            bucket to ordered
        }
        .sortedBy { it.first.ordinal }
}

enum class StringBucket(val sortOrder: Int) {
    TODAY(0), YESTERDAY(1), WEEK(2), EARLIER(3);

    @Composable
    fun displayLabel(): String = when (this) {
        TODAY -> stringResource(R.string.notif_center_bucket_today)
        YESTERDAY -> stringResource(R.string.notif_center_bucket_yesterday)
        WEEK -> stringResource(R.string.notif_center_bucket_week)
        EARLIER -> stringResource(R.string.notif_center_bucket_earlier)
    }
}

@Composable
internal fun relativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> stringResource(R.string.time_just_now)
        diff < 3600_000 -> stringResource(R.string.notif_center_minutes_ago, (diff / 60_000).toInt())
        diff < 86_400_000 -> stringResource(R.string.notif_center_hours_ago, (diff / 3600_000).toInt())
        else -> stringResource(R.string.notif_center_days_ago, (diff / 86_400_000).toInt())
    }
}

// G156：原私有副本（18 行）收敛到 ui/component/SearchHighlightText.kt，此处仅剩薄包装。
@Composable
internal fun highlightedText(text: String, query: String): AnnotatedString {
    val (c, bg) = SearchHighlightSurface
    return com.maodouchat.ui.component.highlightedText(text, query, c, bg)
}
