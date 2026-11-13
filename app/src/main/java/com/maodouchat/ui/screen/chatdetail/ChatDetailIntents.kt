package com.maodouchat.ui.screen.chatdetail

import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.maodouchat.R
import com.maodouchat.core.model.AiCostVisibilityPolicy
import com.maodouchat.core.model.CallType
import com.maodouchat.domain.messaging.AiStreamStatusText
import com.maodouchat.domain.messaging.resolveAiStreamStatusText

/**
 * 聊天详情页的「外部意图与权限」工具（G111 从 `ChatDetailComponents.kt` 拆出，原 88 行）。
 *
 * 四个非 Composable 或极薄 Composable 的工具：
 * - `aiStreamStatusText`：AI 流式错误码 → 用户可读文案（含限流等待秒数）；
 * - `openFile`：把 content/file URI 经 FileProvider 转成可授权视图意图；
 * - `requestVoiceCallPermission` / `requestVideoCallPermissions`：语音/视频通话前的权限申请。
 *
 * 它们被 `ChatDetailRoute`、`ChatDetailComposerExtras`、
 * `ChatDetailAiStatusStrip` 多处共用，放在输入栏文件里名不副实，故独立成文件。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`。
 * 纯搬移，不改判断。
 */

@Composable
internal fun aiStreamStatusText(errorCode: String?): String {
    val base = AiCostVisibilityPolicy.baseErrorCode(errorCode)
    val wait = AiCostVisibilityPolicy.waitSecondsFor(errorCode).coerceAtLeast(1L)
    // Android 的 getString(id, vararg) 会忽略多余实参，所以统一把 wait 传下去，
    // 只有限流那一支真的用到它。
    return stringResource(aiStreamStatusRes(base), wait)
}

// AI 流式错误码 → 提示文案资源：种类判定在 domain（resolveAiStreamStatusText），这里只做枚举→R 映射。
internal fun aiStreamStatusRes(base: String): Int =
    aiStreamStatusResId(resolveAiStreamStatusText(base))

internal fun aiStreamStatusResId(kind: AiStreamStatusText): Int = when (kind) {
    AiStreamStatusText.CANCELLED -> R.string.chat_ai_stream_cancelled
    AiStreamStatusText.RATE_LIMITED -> R.string.chat_ai_stream_rate_limited
    AiStreamStatusText.QUOTA_EXCEEDED -> R.string.chat_ai_stream_quota_exceeded
    AiStreamStatusText.NETWORK -> R.string.chat_ai_operation_network_failed
    AiStreamStatusText.OUTCOME_UNKNOWN -> R.string.chat_ai_operation_outcome_unknown
    AiStreamStatusText.SERVER -> R.string.chat_ai_operation_server_failed
    AiStreamStatusText.INVALID_RESULT -> R.string.chat_ai_operation_invalid_result
    AiStreamStatusText.FAILED -> R.string.chat_ai_operation_failed
}

internal fun openFile(context: android.content.Context, contentUri: String) {
    runCatching {
        val parsed = contentUri.toUri()
        val uri = if (parsed.scheme == "file") {
            val file = java.io.File(requireNotNull(parsed.path))
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else parsed
        val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(parsed.toString()).lowercase()
        val mime = context.contentResolver.getType(uri)
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            android.widget.Toast.makeText(context, context.getString(R.string.chat_no_file_app), android.widget.Toast.LENGTH_SHORT).show()
        }
    }.onFailure { android.widget.Toast.makeText(context, context.getString(R.string.chat_open_file_failed), android.widget.Toast.LENGTH_SHORT).show() }
}


internal fun requestVoiceCallPermission(
    context: Context,
    launchPermissionRequest: (String) -> Unit,
    contactId: String,
    contactName: String,
    onVoiceCall: (String, String) -> Unit
) {
    // 权限集合走统一门；语音通话只需麦克风，缺失时只请求这一项。
    if (com.maodouchat.call.CallPermissionGate.hasPermissions(context, CallType.AUDIO)) {
        onVoiceCall(contactId, contactName)
    } else {
        launchPermissionRequest(Manifest.permission.RECORD_AUDIO)
    }
}

internal fun requestVideoCallPermissions(
    context: Context,
    launchPermissionRequest: (Array<String>) -> Unit,
    contactId: String,
    contactName: String,
    onVideoCall: (String, String) -> Unit
) {
    val missing = com.maodouchat.call.CallPermissionGate.missingPermissions(context, CallType.VIDEO)
    if (missing.isEmpty()) {
        onVideoCall(contactId, contactName)
    } else {
        launchPermissionRequest(missing)
    }
}
