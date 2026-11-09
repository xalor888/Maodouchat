package com.maodouchat.domain.messaging

/** 附件传输收尾时序常量（纯逻辑）：孤儿文件宽限与陈旧 SENDING 认领窗口。 */
object AttachmentTransferTiming {

    /** 孤儿附件文件宽限：24h——超过此时长且无归属行的缓存文件可删。 */
    const val ORPHAN_GRACE_MS = 24L * 60L * 60L * 1_000L

    /** SENDING claim 超过此时长即视为进程死亡后遗留，可释放重领。 */
    const val STALE_SENDING_MS = 2L * 60L * 1_000L
}
