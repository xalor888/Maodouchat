package com.maodouchat.util

/**
 * 群玩法编解码共用的字段转义（G328c 从 `GroupPlayPolicy` 提出为包内共享）。
 *
 * 原先它是 `GroupPlayPolicy` 的两个私有函数；把模式编解码拆到
 * `GroupPlayClassicPolicy` / `GroupPlayModePolicy` 之后，三处都要用同一套转义——
 * 复制两份会让「`|`/`^` 的转义规则」有两个可能漂移的定义，所以提到这里。
 */
internal object GroupPlayFieldEscape {
    fun esc(s: String): String = s.replace("|", "\u0001").replace("^", "\u0002")
    fun unesc(s: String): String = s.replace("\u0001", "|").replace("\u0002", "^")
}
