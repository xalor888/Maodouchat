package com.maodouchat.domain.groups

import com.maodouchat.core.model.MediaCenterCategory

enum class GroupDetailTab(val mediaCategory: MediaCenterCategory? = null) {
    MEMBERS,
    AUDIT,
    MEDIA(MediaCenterCategory.MEDIA),
    FILES(MediaCenterCategory.FILES),
    VOICE(MediaCenterCategory.VOICE),
    LOCATION(MediaCenterCategory.LOCATION),
    LINKS(MediaCenterCategory.LINKS),
}
