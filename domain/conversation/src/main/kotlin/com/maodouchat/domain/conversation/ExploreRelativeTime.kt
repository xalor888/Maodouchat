package com.maodouchat.domain.conversation

// Explore 动态相对时间分桶与帖子可见性归一化：自 app 侧 ExploreRelativeTime.kt
// 逐字平移，R.string/plural 映射留 app 侧 @Composable 壳。
sealed interface ExploreRelativeTimeBucket {
    data object JustNow : ExploreRelativeTimeBucket
    data class Minutes(val count: Int) : ExploreRelativeTimeBucket
    data class Hours(val count: Int) : ExploreRelativeTimeBucket
    data class Days(val count: Int) : ExploreRelativeTimeBucket
}

fun relativeTimeBucket(diffMillis: Long): ExploreRelativeTimeBucket = when {
    diffMillis < 60_000 -> ExploreRelativeTimeBucket.JustNow
    diffMillis < 3_600_000 -> ExploreRelativeTimeBucket.Minutes((diffMillis / 60_000).toInt())
    diffMillis < 86_400_000 -> ExploreRelativeTimeBucket.Hours((diffMillis / 3_600_000).toInt())
    else -> ExploreRelativeTimeBucket.Days((diffMillis / 86_400_000).toInt())
}

enum class ExploreVisibilityOption {
    Public,
    Contacts,
    Private,
}

fun exploreVisibilityOption(value: String): ExploreVisibilityOption = when (value) {
    "CONTACTS" -> ExploreVisibilityOption.Contacts
    "PRIVATE" -> ExploreVisibilityOption.Private
    else -> ExploreVisibilityOption.Public
}
