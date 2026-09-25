package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * G335：**密聊门禁与设备风险提示的会话内进度**。
 *
 * 归成一族：这 6 个状态是同一段「进入密聊 → 逐个门禁 → 通过或锁住」流程的**进度标记**，
 * 它们之间还有顺序约束（`secretGateBlocked` 会短路后面的设备校验；`deviceRiskLocked` 会阻止再弹窗）。
 * 原先散在 Route 里 4 处 LaunchedEffect 之间，读代码时很难看出「谁在什么条件下把谁置位」。
 *
 * 注意与 Route 里那两个 `rememberSaveable` 的门禁弹窗可见性区分：
 * 那把 `Secret2faGatePrefs` / `SecretNewDeviceRiskPrefs` 是**跨会话持久**的偏好（存在 prefs 里），
 * 本类这些是**本次进入该会话**的一次性进度——退出会话就该重算，**不该跨进程存活**
 * （进程重建后直接把用户锁在「已锁」态是坏体验，重新评估才是对的）。
 */
internal class ChatDetailSecretGateState {
    /** 用户本次已手动关掉密聊门禁提示（同一会话内不再弹）。 */
    var secretGateDismissed by mutableStateOf(false)

    /** 本次进入密聊被门禁挡下（安全码/设备校验未通过时为 true，会短路后续步骤）。 */
    var secretGateBlocked by mutableStateOf(false)

    /** 本次进入密聊已提示过「验证对方设备」。 */
    var deviceVerifyPrompted by mutableStateOf(false)

    /** 本次进入密聊已提示过「新设备风险」。 */
    var deviceRiskPrompted by mutableStateOf(false)

    /** 新设备风险弹窗可见性（由上面那个 prompted 标记与评估结果共同决定）。 */
    var showDeviceRiskDialog by mutableStateOf(false)

    /** 设备风险被判定为锁定态（不再重复打扰，但仍以横幅提示）。 */
    var deviceRiskLocked by mutableStateOf(false)

    /** 换会话/退出密聊时整体复位——进度标记必须一起清，单独清一个会留下半截门禁状态。 */
    fun reset() {
        secretGateDismissed = false
        secretGateBlocked = false
        deviceVerifyPrompted = false
        deviceRiskPrompted = false
        showDeviceRiskDialog = false
        deviceRiskLocked = false
    }
}
