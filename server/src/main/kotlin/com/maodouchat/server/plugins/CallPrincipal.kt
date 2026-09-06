package com.maodouchat.server.plugins

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal

/**
 * 路由层调用方身份提取（B02）。
 *
 * 收敛 200+ 处 `call.principal<JWTPrincipal>()!!.payload.subject` 样板：
 * 缺失 principal 时行为与原来一致（Kotlin `!!` 抛错，由上层 StatusPages 处理）。
 */
fun ApplicationCall.requireUserId(): String = principal<JWTPrincipal>()!!.payload.subject

/**
 * 可选身份（公开端点的 viewer 语义）：无 principal 时返回 null，
 * 与 `call.principal<JWTPrincipal>()?.payload?.subject` 等价。
 */
fun ApplicationCall.optionalUserId(): String? = principal<JWTPrincipal>()?.payload?.subject
