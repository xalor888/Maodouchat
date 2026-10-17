package com.maodouchat.ui.screen.chatdetail

import android.content.Context

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

import com.maodouchat.R

// 从 ChatDetailRoute.kt 纯搬移：密聊门禁副作用簇（2FA 门禁 / 设备核验 / 新设备风控）抽出，块字节级一致。
@Composable
internal fun ChatDetailSecretGateEffects(
    state: ChatDetailUiState,
    secretActive: Boolean,
    secretGate: ChatDetailSecretGateState,
    viewModel: ChatDetailViewModel,
    context: Context,
) {
    // B2 双因素门禁（2faz）：进入密聊会话前需系统认证，验证后窗口期内免重复验证
    // G335：密聊门禁进度收进持有类（见 ChatDetailSecretGateState.kt）
    LaunchedEffect(secretActive, state.chat?.id, secretGate.secretGateDismissed) {
        if (!secretActive) {
            secretGate.secretGateBlocked = false
            return@LaunchedEffect
        }
        secretGate.secretGateDismissed = false
        if (com.maodouchat.util.Secret2faGatePrefs.isGateOpen(context)) {
            secretGate.secretGateBlocked = false
            return@LaunchedEffect
        }
        secretGate.secretGateBlocked = true
        com.maodouchat.security.SensitiveActionGate.confirmSystemAuth(
            context = context,
            title = context.getString(R.string.secret_2fa_gate_title),
            subtitle = context.getString(R.string.secret_2fa_gate_subtitle),
            onSuccess = {
                secretGate.secretGateBlocked = false
                com.maodouchat.util.Secret2faGatePrefs.markVerified(context)
            },
            onFailure = {
                secretGate.secretGateDismissed = true
                Toast.makeText(context, context.getString(R.string.secret_2fa_gate_verify_hint), Toast.LENGTH_LONG).show()
            }
        )
    }

    // B2 设备核验（dvz）：进入密聊时若开关开启且对端指纹未核验 → 自动弹出安全码页；用户验证后不再弹
    LaunchedEffect(secretActive, state.chat?.id, state.contactIdentityFingerprint, secretGate.secretGateBlocked) {
        if (!secretActive) {
            secretGate.deviceVerifyPrompted = false
            return@LaunchedEffect
        }
        if (secretGate.secretGateBlocked) return@LaunchedEffect
        if (secretGate.deviceVerifyPrompted) return@LaunchedEffect
        if (!com.maodouchat.util.SecretDeviceVerifyPrefs.isEnabled(context)) return@LaunchedEffect
        val fp = state.contactIdentityFingerprint?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (com.maodouchat.util.SecretDeviceVerifyPrefs.isFingerprintVerified(context, fp)) return@LaunchedEffect
        secretGate.deviceVerifyPrompted = true
        viewModel.showSafetyCodeDialog()
    }

    // B2 新设备风控（ndz）：首次进入密聊需登记本机设备指纹；未登记提示并保持锁定
    // 设备指纹 = 安装级 UUID（跨重启稳定；本应用关闭系统备份，重装后 SharedPreferences
    // 清空 → 重新生成 → 视为新设备）。此前用「当前日期窗」导致每天变化，已登记设备
    // 次日被误判为新设备，改为稳定的安装标识。
    val deviceRiskId = remember(context) {
        com.maodouchat.push.PushRegistrationManager.currentDeviceId(context)
    }
    LaunchedEffect(secretActive, state.chat?.id, secretGate.deviceRiskPrompted, secretGate.deviceRiskLocked, secretGate.secretGateBlocked) {
        if (!secretActive) {
            secretGate.deviceRiskPrompted = false
            return@LaunchedEffect
        }
        if (secretGate.secretGateBlocked) return@LaunchedEffect
        if (secretGate.deviceRiskPrompted) return@LaunchedEffect
        if (!com.maodouchat.util.SecretNewDeviceRiskPrefs.isEnabled(context)) return@LaunchedEffect
        if (deviceRiskId.isBlank() || com.maodouchat.util.SecretNewDeviceRiskPrefs.isDeviceTrusted(context, deviceRiskId)) return@LaunchedEffect
        secretGate.deviceRiskPrompted = true
        secretGate.showDeviceRiskDialog = true
    }
    ChatDetailDeviceRiskDialog(
        secretGate = secretGate,
        deviceRiskId = deviceRiskId,
    )
}
