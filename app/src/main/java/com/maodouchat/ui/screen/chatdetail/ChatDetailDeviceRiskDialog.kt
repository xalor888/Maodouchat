package com.maodouchat.ui.screen.chatdetail

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.maodouchat.R


/** B2 新设备风控提示弹窗的调用点（从 ChatDetailRoute.kt 纯搬移，块字节级一致）。 */
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调内读取，非组合作用域
internal fun ChatDetailDeviceRiskDialog(
    secretGate: ChatDetailSecretGateState,
    deviceRiskId: String,
) {
    val context = LocalContext.current
    NewDeviceRiskPromptDialog(
        onRegister = {
            secretGate.showDeviceRiskDialog = false
            secretGate.deviceRiskLocked = false
            if (deviceRiskId.isNotBlank()) {
                com.maodouchat.util.SecretNewDeviceRiskPrefs.registerDevice(context, deviceRiskId)
                Toast.makeText(context, context.getString(R.string.secret_new_device_risk_registered), Toast.LENGTH_SHORT).show()
            }
        },
        onKeepLocked = {
            secretGate.showDeviceRiskDialog = false
            secretGate.deviceRiskLocked = true
            Toast.makeText(context, context.getString(R.string.secret_new_device_risk_locked), Toast.LENGTH_LONG).show()
        },
    )
}
