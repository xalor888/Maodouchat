package com.maodouchat.ui.screen.chatdetail

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.maodouchat.R
import com.maodouchat.data.local.entity.AiOperationError

/**
 * 聊天详情页的「外部意图与权限」工具（G111 从 `ChatDetailComponents.kt` 拆出，原 88 行）。
 *
 * 四个非 Composable 或极薄 Composable 的工具：
 * - `aiStreamStatusText`：AI 流式错误码 → 用户可读文案（含限流等待秒数）；
 * - `openFile`：把 content/file URI 经 FileProvider 转成可授权视图意图；
 * - `requestVoiceCallPermission` / `requestVideoCallPermissions`：语音/视频通话前的权限申请。
 *
 * 它们被 `ChatDetailRoute`、`CallNavigation`、`ChatDetailComposerExtras`、
 * `ChatDetailAiStatusStrip` 多处共用，放在输入栏文件里名不副实，故独立成文件。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`。
 * 纯搬移，不改判断。
 */

@Composable
internal fun aiStreamStatusText(errorCode: String?): String {
    val base = com.maodouchat.ai.AiCostVisibilityPolicy.baseErrorCode(errorCode)
    val wait = com.maodouchat.ai.AiCostVisibilityPolicy.waitSecondsFor(errorCode).coerceAtLeast(1L)
    // Android 的 getString(id, vararg) 会忽略多余实参，所以统一把 wait 传下去，
    // 只有限流那一支真的用到它。
    return stringResource(aiStreamStatusRes(base), wait)
}

/**
 * AI 流式错误码 → 提示文案资源（G161 从 `aiStreamStatusText` 抽出的纯映射）。
 *
 * 抽开之前这段 `when` 和 `stringResource` 耦在一起，只能靠仪器测试覆盖；
 * 现在映射本身是纯函数，普通 JVM 单测就能逐分支断言。
 *
 * 三处「多码一支」是**有意的**，改动时必须连测试一起改：
 * - `TIMEOUT` / `OUTCOME_UNKNOWN` / `UNKNOWN` 共用「请求可能已被处理」——
 *   三者的用户动作完全相同（确认后手动重试），文案分开只会让人以为区别对待；
 * - `EMPTY_RESULT` / `INVALID_RESPONSE` 共用「未返回有效结果」。
 */
internal fun aiStreamStatusRes(base: String): Int = when (base) {
    "CANCELLED" -> R.string.chat_ai_stream_cancelled
    AiOperationError.RATE_LIMITED -> R.string.chat_ai_stream_rate_limited
    AiOperationError.QUOTA_EXCEEDED -> R.string.chat_ai_stream_quota_exceeded
    AiOperationError.NETWORK -> R.string.chat_ai_operation_network_failed
    AiOperationError.TIMEOUT, AiOperationError.OUTCOME_UNKNOWN, AiOperationError.UNKNOWN ->
        R.string.chat_ai_operation_outcome_unknown
    AiOperationError.SERVER -> R.string.chat_ai_operation_server_failed
    AiOperationError.EMPTY_RESULT, AiOperationError.INVALID_RESPONSE ->
        R.string.chat_ai_operation_invalid_result
    else -> R.string.chat_ai_operation_failed
}

internal fun openFile(context: android.content.Context, contentUri: String) {
    runCatching {
        val parsed = android.net.Uri.parse(contentUri)
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
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
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
    val missingPermissions = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    if (missingPermissions.isEmpty()) {
        onVideoCall(contactId, contactName)
    } else {
        launchPermissionRequest(missingPermissions.toTypedArray())
    }
}
