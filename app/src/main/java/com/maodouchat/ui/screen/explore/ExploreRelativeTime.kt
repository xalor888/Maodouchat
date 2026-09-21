package com.maodouchat.ui.screen.explore

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.maodouchat.R

/**
 * Explore 模块的公共小组件（G175 从 5 处私有副本收敛而来）。
 *
 * `relativeTime`：ExplorePostDetailScreen / ExploreMomentsScreen /
 * AuthorProfileScreen 三处**逐字相同**的 18 行手写分档逻辑。
 *
 * 注意：本文件里还有另一个 `relativeTime` 形态（ExplorePostCards 用
 * `RelativeTimePolicy` + `DateUtils.getRelativeTimeSpanString`），
 * 二者**不是**同一个实现——那个多一层「刚刚」判定并用系统本地化区间。
 * 本轮只收敛三份完全相同的，不擅自统一两套语义。
 */

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前。 */
@Composable
internal fun relativeTime(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000 -> stringResource(R.string.time_just_now)
        diff < 3600_000 -> {
            val count = (diff / 60_000).toInt()
            pluralStringResource(R.plurals.time_minutes_ago, count, count)
        }
        diff < 86_400_000 -> {
            val count = (diff / 3600_000).toInt()
            pluralStringResource(R.plurals.time_hours_ago, count, count)
        }
        else -> {
            val count = (diff / 86_400_000).toInt()
            pluralStringResource(R.plurals.time_days_ago, count, count)
        }
    }
}

/** 帖子可见性选项的本地化标签；未知值回落「公开」。 */
@Composable
internal fun visibilityOptionLabel(value: String): String = when (value) {
    "CONTACTS" -> stringResource(R.string.explore_visibility_contacts)
    "PRIVATE" -> stringResource(R.string.explore_visibility_private)
    else -> stringResource(R.string.explore_visibility_public)
}
