package com.maodouchat.explore.repository

import com.maodouchat.network.api.MediaApi
import com.maodouchat.network.api.MediaApiClient
import com.maodouchat.security.BackgroundSessionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

enum class UploadStatus {
    IDLE,
    QUEUED,
    UPLOADING,
    SUCCESS,
    FAILED,
    CANCELLED
}

data class UploadItem(
    val id: String,
    val ownerUserId: String,
    val base64Data: String,
    val status: UploadStatus = UploadStatus.QUEUED,
    val uploadUrl: String? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3
)

interface MediaUploadQueue {
    val itemsFlow: StateFlow<Map<String, UploadItem>>

    fun enqueue(id: String, ownerUserId: String, base64Data: String, maxRetries: Int = 3)
    fun startUpload(id: String, token: String): Job?
    fun retry(id: String, token: String): Job?
    fun cancel(id: String)
    suspend fun discardUploaded(token: String, ownerUserId: String, url: String): Result<Unit>
    fun clear()
}

class DefaultMediaUploadQueue(
    private val mediaApi: MediaApi = MediaApiClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher),
    private val sessionGateCheck: (expectedUserId: String, liveToken: String?) -> Boolean = { expectedUserId, liveToken ->
        BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
            liveToken = liveToken,
            liveUserId = expectedUserId
        )
    }
) : MediaUploadQueue {

    private val _itemsFlow = MutableStateFlow<Map<String, UploadItem>>(emptyMap())
    override val itemsFlow: StateFlow<Map<String, UploadItem>> = _itemsFlow.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()

    override fun enqueue(id: String, ownerUserId: String, base64Data: String, maxRetries: Int) {
        val item = UploadItem(
            id = id,
            ownerUserId = ownerUserId,
            base64Data = base64Data,
            status = UploadStatus.QUEUED,
            maxRetries = maxRetries
        )
        _itemsFlow.update { current ->
            current + (id to item)
        }
    }

    override fun startUpload(id: String, token: String): Job? {
        val item = _itemsFlow.value[id] ?: return null
        if (item.status == UploadStatus.UPLOADING) {
            return activeJobs[id]
        }

        val job = scope.launch {
            try {
                if (!sessionGateCheck(item.ownerUserId, token)) {
                    _itemsFlow.update { current ->
                        val existing = current[id] ?: return@update current
                        current + (id to existing.copy(
                            status = UploadStatus.FAILED,
                            errorMessage = "Session invalid"
                        ))
                    }
                    return@launch
                }

                _itemsFlow.update { current ->
                    val existing = current[id] ?: return@update current
                    current + (id to existing.copy(
                        status = UploadStatus.UPLOADING,
                        errorMessage = null
                    ))
                }

                val uploadResult = mediaApi.uploadPostImage(token, item.base64Data)

                if (!sessionGateCheck(item.ownerUserId, token)) {
                    _itemsFlow.update { current ->
                        val existing = current[id] ?: return@update current
                        current + (id to existing.copy(
                            status = UploadStatus.FAILED,
                            errorMessage = "Session changed after upload"
                        ))
                    }
                    return@launch
                }

                uploadResult.fold(
                    onSuccess = { url ->
                        _itemsFlow.update { current ->
                            val existing = current[id] ?: return@update current
                            current + (id to existing.copy(
                                status = UploadStatus.SUCCESS,
                                uploadUrl = url,
                                errorMessage = null
                            ))
                        }
                    },
                    onFailure = { error ->
                        _itemsFlow.update { current ->
                            val existing = current[id] ?: return@update current
                            val newRetryCount = existing.retryCount + 1
                            current + (id to existing.copy(
                                status = UploadStatus.FAILED,
                                retryCount = newRetryCount,
                                errorMessage = error.message ?: "Upload failed"
                            ))
                        }
                    }
                )
            } catch (e: CancellationException) {
                _itemsFlow.update { current ->
                    val existing = current[id] ?: return@update current
                    current + (id to existing.copy(status = UploadStatus.CANCELLED))
                }
                throw e
            } catch (e: Exception) {
                _itemsFlow.update { current ->
                    val existing = current[id] ?: return@update current
                    current + (id to existing.copy(
                        status = UploadStatus.FAILED,
                        errorMessage = e.message ?: "Upload error"
                    ))
                }
            } finally {
                activeJobs.remove(id)
            }
        }

        activeJobs[id] = job
        return job
    }

    override fun retry(id: String, token: String): Job? {
        val item = _itemsFlow.value[id] ?: return null
        _itemsFlow.update { current ->
            current + (id to item.copy(status = UploadStatus.QUEUED, errorMessage = null))
        }
        return startUpload(id, token)
    }

    override fun cancel(id: String) {
        activeJobs.remove(id)?.cancel()
        _itemsFlow.update { current ->
            val item = current[id] ?: return@update current
            current + (id to item.copy(status = UploadStatus.CANCELLED))
        }
    }

    override suspend fun discardUploaded(token: String, ownerUserId: String, url: String): Result<Unit> {
        if (!sessionGateCheck(ownerUserId, token)) {
            return Result.failure(IllegalStateException("Session invalid"))
        }
        return mediaApi.discardPostImage(token, url)
    }

    override fun clear() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        _itemsFlow.value = emptyMap()
    }
}
