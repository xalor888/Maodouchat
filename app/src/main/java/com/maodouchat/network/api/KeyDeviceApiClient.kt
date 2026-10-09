package com.maodouchat.network.api

import com.maodouchat.BuildConfig
import com.maodouchat.util.toHexString
import com.maodouchat.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

internal object KeyDeviceApiClient : KeyDeviceApi {
private val json get() = ApiService.json
private val JSON_MEDIA get() = ApiService.JSON_MEDIA
private val ATTACHMENT_CHUNK_BYTES get() = ApiService.ATTACHMENT_CHUNK_BYTES
private val ATTACHMENT_CHUNK_MAX_ATTEMPTS get() = ApiService.ATTACHMENT_CHUNK_MAX_ATTEMPTS
private val ATTACHMENT_ID_REGEX get() = ApiService.ATTACHMENT_ID_REGEX
private val POST_IMAGE_FILENAME_REGEX get() = ApiService.POST_IMAGE_FILENAME_REGEX
private fun jsonBody(value: String) = value.toRequestBody(ApiService.JSON_MEDIA)
private suspend fun <T> send(request: Request, serializer: kotlinx.serialization.KSerializer<T>): Result<T> = ApiService.send(request, serializer)
private suspend fun sendUnit(request: Request): Result<Unit> = ApiService.sendUnit(request)
private suspend fun executeForText(request: Request, errorPrefix: String): Result<String> = ApiService.executeForText(request, errorPrefix)
private suspend fun executeStreamingWithRefresh(request: Request): Response = ApiService.executeStreamingWithRefresh(request)
private fun parseError(body: String): String? = ApiService.parseError(body)


override suspend fun getSealedSenderCertificate(token: String, deviceId: Int): Result<String> = executeForText(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/e2ee/sealed-sender/certificate?deviceId=$deviceId")
        .header("Authorization", "Bearer $token")
        .get()
        .build(),
    "sealed_sender_cert"
)

override suspend fun getDevices(token: String, userId: String, currentDeviceId: Int?): Result<List<DeviceInfoDto>> {
    val suffix = currentDeviceId?.let { "?currentDeviceId=$it" }.orEmpty()
    return send(
        Request.Builder().url("${ApiConfig.BASE_URL}/api/keys/$userId/devices$suffix").addHeader("Authorization", "Bearer $token").get().build(),
        ListSerializer(DeviceInfoDto.serializer())
    )
}

/** Signal 密钥包上传：走 executeWithRefresh，冷启动 JWT 过期时先 refresh */

override suspend fun uploadKeys(token: String, request: UploadKeysRequest): Result<Unit> =
    sendUnit(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/keys/upload")
            .addHeader("Authorization", "Bearer $token")
            .post(jsonBody(json.encodeToString(UploadKeysRequest.serializer(), request)))
            .build()
    )

override suspend fun getPreKeyBundle(token: String, userId: String): Result<PreKeyBundleDto> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/keys/$userId/prekey-bundle")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        PreKeyBundleDto.serializer()
    )

override suspend fun getDevicePreKeyBundle(token: String, userId: String, deviceId: Int): Result<DevicePreKeyBundleDto> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/keys/$userId/devices/$deviceId/prekey-bundle")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        DevicePreKeyBundleDto.serializer()
    )

override suspend fun getDevicePreKeyBundles(token: String, userId: String): Result<List<DevicePreKeyBundleDto>> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/keys/$userId/prekey-bundles")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        ListSerializer(DevicePreKeyBundleDto.serializer())
    )

override suspend fun removeMyDevice(token: String, deviceId: Int): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/keys/devices/$deviceId").addHeader("Authorization", "Bearer $token").delete().build())

override suspend fun renameMyDevice(token: String, deviceId: Int, deviceName: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/keys/devices/$deviceId/name").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateDeviceNameRequest.serializer(), UpdateDeviceNameRequest(deviceName)))).build())

override suspend fun confirmMyDevice(token: String, deviceId: Int, approverDeviceId: Int, signature: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/keys/devices/$deviceId/confirm").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(ConfirmDeviceRequest.serializer(), ConfirmDeviceRequest(approverDeviceId, signature)))).build())
}
