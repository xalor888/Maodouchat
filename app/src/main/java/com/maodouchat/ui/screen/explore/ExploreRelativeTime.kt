package com.maodouchat.ui.screen.explore

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.domain.conversation.ExploreRelativeTimeBucket
import com.maodouchat.domain.conversation.ExploreVisibilityOption
import com.maodouchat.domain.conversation.exploreVisibilityOption
import com.maodouchat.domain.conversation.relativeTimeBucket

/**
 * Explore 模块的公共小组件（G175 从 5 处私有副本收敛而来）。
 *
 * `relativeTime`：ExplorePostDetailScreen / ExploreMomentsScreen /
 * AuthorProfileScreen 三处共用的分档逻辑，分桶判定已迁入 `:domain:conversation`，
 * 本文件只留 R.string/plural 映射壳。
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
    return when (val bucket = relativeTimeBucket(diff)) {
        ExploreRelativeTimeBucket.JustNow -> stringResource(R.string.time_just_now)
        is ExploreRelativeTimeBucket.Minutes ->
            pluralStringResource(R.plurals.time_minutes_ago, bucket.count, bucket.count)
        is ExploreRelativeTimeBucket.Hours ->
            pluralStringResource(R.plurals.time_hours_ago, bucket.count, bucket.count)
        is ExploreRelativeTimeBucket.Days ->
            pluralStringResource(R.plurals.time_days_ago, bucket.count, bucket.count)
    }
}

/** 帖子可见性选项的本地化标签；未知值回落「公开」。 */
@Composable
internal fun visibilityOptionLabel(value: String): String = when (exploreVisibilityOption(value)) {
    ExploreVisibilityOption.Contacts -> stringResource(R.string.explore_visibility_contacts)
    ExploreVisibilityOption.Private -> stringResource(R.string.explore_visibility_private)
    ExploreVisibilityOption.Public -> stringResource(R.string.explore_visibility_public)
}
