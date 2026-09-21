package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager

/**
 * 当前登录用户 id（G174 从 8 处私有副本收敛而来）。
 *
 * 这些 Store / Preferences 都需要按用户隔离数据（key 前缀或分表），
 * 于是每个文件都私有了一份三行的 `currentUserId(context)`。
 * 收敛后，改 userId 的取值口径只需改一处。
 *
 * 返回 null 表示未登录或 id 为空白——调用方据此走「不持久化 / 清空」分支。
 */
internal fun currentUserId(context: Context): String? =
    TokenManager.getInstance(context.applicationContext).getUserId()?.takeIf { it.isNotBlank() }
