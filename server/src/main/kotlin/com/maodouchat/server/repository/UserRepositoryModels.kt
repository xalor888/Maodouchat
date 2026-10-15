package com.maodouchat.server.repository

/** B13：批量处置字段（SUSPEND/POST/MESSAGE 单项，或组合清除）。 */
enum class UserDispositionField { SUSPEND, POST, MESSAGE, MESSAGE_AND_POST, ALL }

/** B13：批量处置语义——EXTEND 保长（maxOf）、SET 直接覆盖、CLEAR 置零。 */
enum class UserDispositionMode { EXTEND, SET, CLEAR }

data class BulkDispositionResult(val updated: List<String>, val skipped: List<String>)

/** B13：批量用户设置字段（searchable/showStatus/showOnline 布尔开关）。 */
enum class UserSettingsField { SEARCHABLE, SHOW_STATUS, SHOW_ONLINE }
