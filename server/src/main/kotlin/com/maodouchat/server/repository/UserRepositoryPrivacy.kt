package com.maodouchat.server.repository

// 隐私设置委托簇：原 UserRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.model.UserPrivacyResponse

fun UserRepository.getPrivacy(userId: String): UserPrivacyResponse? = privacyService.getPrivacy(userId)

fun UserRepository.updatePrivacyWithTransitions(
    userId: String,
    showOnline: Boolean? = null,
    showStatus: Boolean? = null,
    searchable: Boolean? = null,
    defaultPostVisibility: String? = null,
    onlineVisibility: String? = null
): PrivacyUpdateResult? = privacyService.updatePrivacyWithTransitions(userId, showOnline, showStatus, searchable, defaultPostVisibility, onlineVisibility)
