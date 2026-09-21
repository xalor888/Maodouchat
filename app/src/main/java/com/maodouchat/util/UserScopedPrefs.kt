package com.maodouchat.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 「按用户隔离的 SharedPreferences」公共样板（G180 从 4 个 Store 收敛而来）。
 *
 * `GifSearchPreferences` / `QuickPhrasePreferences` / `EmojiRecentPreferences` /
 * `StickerPreferences` 各自都私有了一份 `prefs(context)` 与 `key(prefix, userId)`，
 * 逐字相同。收敛后，隔离约定（用 applicationContext、key 前缀 + userId）只有一处。
 */

/**
 * 取 Store 的 SharedPreferences。
 *
 * 用 `applicationContext` 而不是传入的 context——调用方可能是 Activity，
 * 直接持有会在旋转/重建后持有一个已死的 Context。
 */
internal fun userScopedPrefs(context: Context, prefsName: String): SharedPreferences =
    context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

/**
 * 拼「按用户隔离」的 key。
 *
 * 约定是 `"${prefix}_$userId"`；userId 为空白时调用方应自行短路
 * （各 Store 的 public 方法都先判空），这里不做额外校验以保持纯拼接语义。
 */
internal fun userScopedKey(prefix: String, userId: String): String = "${prefix}_$userId"
