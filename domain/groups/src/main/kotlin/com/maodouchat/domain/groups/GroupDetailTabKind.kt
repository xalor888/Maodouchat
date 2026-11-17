package com.maodouchat.domain.groups


// 群详情页签标签种类；R.string 映射留在 app 侧。
enum class GroupDetailTabKind {
    MEMBERS,
    AUDIT,
    MEDIA,
    FILES,
    VOICE,
    LOCATION,
    LINKS,
}

fun groupDetailTabKind(tab: GroupDetailTab): GroupDetailTabKind =
    when (tab) {
        GroupDetailTab.MEMBERS -> GroupDetailTabKind.MEMBERS
        GroupDetailTab.AUDIT -> GroupDetailTabKind.AUDIT
        GroupDetailTab.MEDIA -> GroupDetailTabKind.MEDIA
        GroupDetailTab.FILES -> GroupDetailTabKind.FILES
        GroupDetailTab.VOICE -> GroupDetailTabKind.VOICE
        GroupDetailTab.LOCATION -> GroupDetailTabKind.LOCATION
        GroupDetailTab.LINKS -> GroupDetailTabKind.LINKS
    }
