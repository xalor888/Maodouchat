package com.maodouchat.ui.screen.groupplay

import androidx.lifecycle.AndroidViewModel

/**
 * 群玩 ViewModel 的公共样板（G173 从三个 ViewModel 的私有副本收敛而来）。
 *
 * `GroupCheckinViewModel` / `GroupPollViewModel` / `GroupPkViewModel`
 * 各自都有一份 `token()` 与 `str(id)`——三个文件里逐字相同的六行。
 * 收成扩展函数后，改 token 读取方式或 i18n 取值方式只需改一处。
 */

/** 当前登录 token；未登录时为空串（调用方据此走「请先登录」分支）。 */
internal fun AndroidViewModel.authToken(): String =
    com.maodouchat.session.CurrentSession.snapshot().token.orEmpty()

/** 取本地化字符串；比在 ViewModel 里散落 `getApplication<Application>().getString(id)` 短。 */
internal fun AndroidViewModel.localizedString(id: Int): String =
    getApplication<android.app.Application>().getString(id)

/** 群玩 ViewModel 共用的工厂：从 SavedStateHandle 取 chatId。 */
internal fun groupPlayChatId(savedStateHandle: androidx.lifecycle.SavedStateHandle): String =
    savedStateHandle["chatId"] as? String ?: ""
