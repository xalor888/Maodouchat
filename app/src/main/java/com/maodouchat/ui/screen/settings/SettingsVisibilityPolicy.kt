package com.maodouchat.ui.screen.settings

/**
 * 帖子可见性的归一化（G179 从两处同构实现收敛而来）。
 *
 * 收敛前有两份：
 * - `SettingsViewModel.normalizeVisibility`：不认识的值回落 **"PUBLIC"**；
 * - `ExploreDraftPolicy.normalizeVisibility`：不认识的值回落 **"PRIVATE"**。
 *
 * ⚠️ **两处回落方向相反**：服务端若返回一个客户端尚不认识的值（例如将来新增的
 * "FRIENDS"），隐私设置页会当成「公开」、而发布器会当成「私密」——
 * 用户在图里看到的和实际发出去的可能是两回事。
 * 这是**待决策**的问题（见 docs/full-project-refactor-checklist.md G179），
 * 本文件不擅自统一，只把「回落值是调用方显式传进来的」这件事暴露出来。
 */

/** 客户端认识的三种可见性。 */
val VISIBILITY_VALUES: Set<String> = setOf("PUBLIC", "CONTACTS", "PRIVATE")

/** 只认 [VISIBILITY_VALUES] 里的值；其余（含空串）回落调用方给的 [fallback]。 */
internal fun normalizeVisibility(value: String, fallback: String): String =
    value.takeIf { it in VISIBILITY_VALUES } ?: fallback
