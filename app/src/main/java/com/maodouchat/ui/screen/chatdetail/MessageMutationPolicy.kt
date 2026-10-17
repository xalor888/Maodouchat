package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind

/**
 * Transport / server ambiguity: the request may already have been applied.
 * Optimistic DELETE/REVOKE/EDIT must not roll back; AI auto-summary may retry later.
 *
 * Matches historical delete/revoke/edit branches:
 * non-ApiException, NETWORK, TIMEOUT, HTTP 408/504/5xx.
 * Business 4xx (except 404 terminal helper) remains definitive failure.
 */
internal fun isAmbiguousTransportFailure(error: Throwable?): Boolean {
    if (error !is ApiException) return true
    return when (error.kind) {
        ApiFailureKind.NETWORK,
        ApiFailureKind.TIMEOUT -> true
        ApiFailureKind.HTTP -> {
            val code = error.statusCode
            // 8.53：429 批量删除命中服务端 60/min 限流——请求未应用，勿回滚（乐观删除保留），
            // 交由 mutation tracker 退避重试；否则 select-all 删 100+ 条会「删一半剩一半」
            code == 408 || code == 429 || code == 504 || (code ?: 0) >= 500
        }
        ApiFailureKind.INVALID_RESPONSE,
        ApiFailureKind.UNEXPECTED -> false
    }
}

/** HTTP 404 on delete/revoke: server already terminal — treat as success, never resurrect. */
internal fun isAlreadyTerminalMutation(error: Throwable?): Boolean =
    error is ApiException && error.kind == ApiFailureKind.HTTP && error.statusCode == 404
