package com.maodouchat.server.repository

/**
 * Escape LIKE pattern special characters (%, _, \) so user input is treated literally.
 *
 * G15：从 `plugins/AdminSupport.kt` 下沉到中立包。它是纯 SQL 关注点，
 * 而 `repository/` 也要用（管理搜索），放在 `plugins/` 里会逼着 repository 反向依赖 plugins。
 */
internal fun escapeLikePattern(input: String): String =
    input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
