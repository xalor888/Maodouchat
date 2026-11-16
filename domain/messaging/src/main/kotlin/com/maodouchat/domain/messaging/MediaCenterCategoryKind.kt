package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MediaCenterCategory

// 媒体中心分类标签种类；分类枚举本身在 :core:model，domain 只做 1:1 映射。
enum class MediaCenterCategoryKind {
    MEDIA,
    FILES,
    VOICE,
    LINKS,
    LOCATION,
}

fun mediaCenterCategoryKind(category: MediaCenterCategory): MediaCenterCategoryKind =
    when (category) {
        MediaCenterCategory.MEDIA -> MediaCenterCategoryKind.MEDIA
        MediaCenterCategory.FILES -> MediaCenterCategoryKind.FILES
        MediaCenterCategory.VOICE -> MediaCenterCategoryKind.VOICE
        MediaCenterCategory.LINKS -> MediaCenterCategoryKind.LINKS
        MediaCenterCategory.LOCATION -> MediaCenterCategoryKind.LOCATION
    }
