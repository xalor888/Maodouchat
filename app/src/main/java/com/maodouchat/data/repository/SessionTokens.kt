package com.maodouchat.data.repository

import com.maodouchat.session.CurrentSession

/**
 * 仓库默认取用的访问令牌（G332）。
 *
 * 为什么放在这里：`ui/` 里大量代码在做同一件事——
 * `val token = tokenManager.getToken().orEmpty()` 然后**只**把这串字符传给一个仓库。
 * 那是「调用方替仓库取凭据」，属于职责放错位置：调用方不该知道凭据从哪来。
 * 现在仓库的参数默认 `null` 时由本函数取当前会话的令牌，调用方直接 `repo.xxx()`。
 *
 * 空会话返回**空串**而不是抛异常：这与调用方原先的 `.orEmpty()` 完全一致——
 * 旧行为就是「没令牌 → 发出去被 401 拒」，改成抛异常会变成新的失败路径。
 *
 * 显式传令牌仍然支持，且**优先**：后台批次（发送队列、重试、定时任务）用的往往是
 * 批次开始那一刻捕获的令牌，那是有意的选择，不能被默认值覆盖。
 *
 * 将来 `core:session` 被采纳（见 `core/session/build.gradle.kts` 里记的采纳顺序）时，
 * 本函数就是它的落点之一——现在留在 data 层，是因为 data 层已经在依赖会话层了。
 */
internal fun currentAccessToken(): String = CurrentSession.snapshot().token.orEmpty()
