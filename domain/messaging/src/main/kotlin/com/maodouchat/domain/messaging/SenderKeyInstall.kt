package com.maodouchat.domain.messaging

import com.maodouchat.core.crypto.SenderKeyDistOutcome

/** Sender Key 分发安装失败的诊断码：收件箱同步器据此走死信。 */
const val SENDER_KEY_INSTALL_FAILURE_MESSAGE = "messaging_v2_sender_key_install_failed"

/** 仅安装失败才 abort；已安装与跳过（幂等重放）都接受。 */
fun isSenderKeyInstallFailure(outcome: SenderKeyDistOutcome): Boolean =
    outcome == SenderKeyDistOutcome.Failed
