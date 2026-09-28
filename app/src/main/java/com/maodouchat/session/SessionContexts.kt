package com.maodouchat.session

import android.content.Context
import com.maodouchat.network.TokenManager

/**
 * 非 ui 的会话上下文访问口：把 `TokenManager` → `SessionContextProvider` 的装配
 * 收在中立包，ui 层（ChatDetailDeps 等）只引用本对象、不再直连 `TokenManager`。
 */
object SessionContexts {

    fun provider(context: Context): SessionContextProvider =
        TokenManagerSessionContextProvider(TokenManager.getInstance(context))
}
