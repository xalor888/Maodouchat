package com.maodouchat.ui.screen.chatdetail

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat

/**
 * `ChatDetailRoute` 里那 9 个 `rememberLauncherForActivityResult` 的宿主（G331）。
 *
 * 为什么要单列：这些 launcher 是「向系统要东西」的边界（图片/视频/文件/GIF/权限），
 * 每一段都自带一段注释解释为什么选这个 contract（例如 Photo Picker 在部分 AVD 上
 * 会把 MainActivity 结束掉、落到桌面，所以图片改用 `GetContent`）。
 * 它们散在三千行的 Route 里，读的人得先翻过一百行状态才有机会看到这些理由。
 *
 * 搬移是**逐字搬**：除下面三处外没有任何行为改动。
 * 1. 回调用**参数**注入（`onImagePicked` 等），而不是在这里直接写 ViewModel 字段——
 *    否则这个文件又变成「谁都能往上挂东西」的地方；
 * 2. 字符串（四个权限拒绝提示）由调用方给，本文件不做资源 id 映射；
 * 3. 返回一个 [ChatDetailPickers] 袋子，调用方按键取用。
 *
 * 注意 `gifMediaPermissionLauncher` 的回调是**空的**：`GifSearchDialog` 在授权后
 * 重新组合时会自己重新加载。这是原有行为，不是漏写。
 */
internal class ChatDetailPickers(
    val image: ActivityResultLauncher<String>,
    val video: ActivityResultLauncher<String>,
    val file: ActivityResultLauncher<Array<String>>,
    val gif: ActivityResultLauncher<Array<String>>,
    val gifMediaPermission: ActivityResultLauncher<String>,
    val recordAudioPermission: ActivityResultLauncher<String>,
    val voiceCallPermission: ActivityResultLauncher<String>,
    val videoCallPermission: ActivityResultLauncher<Array<String>>,
    val locationPermission: ActivityResultLauncher<Array<String>>,
)

/** 权限被拒时的提示文案（调用方用 `stringResource` 取好传进来）。 */
internal class ChatDetailPermissionMessages(
    val record: String,
    val voiceCall: String,
    val videoCall: String,
    val location: String,
)

@Composable
internal fun rememberChatDetailPickers(
    messages: ChatDetailPermissionMessages,
    onImagePicked: (Uri) -> Unit,
    onVideoPicked: (Uri) -> Unit,
    onFilePicked: (Uri) -> Unit,
    onGifPicked: (Uri) -> Unit,
    onRecordPermissionGranted: () -> Unit,
    onVoiceCallGranted: () -> Unit,
    onVideoCallGranted: () -> Unit,
    /** 定位授权且「本轮是为共享位置而申请」时调用（由调用方决定是开弹窗还是直接上报）。 */
    onLocationGranted: () -> Unit,
): ChatDetailPickers {
    val context = androidx.compose.ui.platform.LocalContext.current

    /** 三处 picker 都要「拿一次持久化读权限」，失败不致命（部分 provider 不支持）。 */
    fun persistReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            persistReadPermission(it)
            onImagePicked(it)
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        // 0.69：视频改为先预览确认（与图片一致），确认后才发送
        uri?.let {
            persistReadPermission(it)
            onVideoPicked(it)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            persistReadPermission(it)
            onFilePicked(it)
        }
    }

    val gifPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            persistReadPermission(it)
            onGifPicked(it)
        }
    }

    val gifMediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* GifSearchDialog reloads when recomposed after grant */ }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onRecordPermissionGranted()
        else Toast.makeText(context, messages.record, Toast.LENGTH_SHORT).show()
    }

    val voiceCallPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onVoiceCallGranted()
        else Toast.makeText(context, messages.voiceCall, Toast.LENGTH_SHORT).show()
    }

    val videoCallPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val hasAudio = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val hasCamera = grants[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (hasAudio && hasCamera) onVideoCallGranted()
        else Toast.makeText(context, messages.videoCall, Toast.LENGTH_SHORT).show()
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            com.maodouchat.util.LocationProvider.hasLocationPermission(context)
        if (granted) {
            onLocationGranted()
        } else {
            Toast.makeText(context, messages.location, Toast.LENGTH_SHORT).show()
        }
    }

    return ChatDetailPickers(
        image = imagePickerLauncher,
        video = videoPickerLauncher,
        file = filePickerLauncher,
        gif = gifPickerLauncher,
        gifMediaPermission = gifMediaPermissionLauncher,
        recordAudioPermission = recordAudioPermissionLauncher,
        voiceCallPermission = voiceCallPermissionLauncher,
        videoCallPermission = videoCallPermissionLauncher,
        locationPermission = locationPermissionLauncher,
    )
}
