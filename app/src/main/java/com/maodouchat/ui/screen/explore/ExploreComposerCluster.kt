package com.maodouchat.ui.screen.explore

import android.net.Uri
import com.maodouchat.R
import com.maodouchat.explore.policy.PostVisibility
import com.maodouchat.explore.repository.UploadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import com.maodouchat.explore.policy.ExploreDraftPolicy

// 发布器一族：发帖/草稿/图片上传。纯搬移，调用点与协程语义不变。
    fun ExploreOrchestrator.publishPost() {
        val state = _uiState.value
        if (!state.canPublish) return
        val session = feedController.currentSession()
        if (session == null || !com.maodouchat.session.CurrentSession.hasSession()) {
            _uiState.update { it.copy(errorMessage = text(R.string.explore_login_required)) }
            return
        }
        val content = state.composerText.trim()
        val imageUrls = state.readyImageUrls
        val visibility = PostVisibility.fromString(state.selectedVisibility)

        _uiState.update { it.copy(isPublishing = true, errorMessage = null) }
        scope.launch {
            publishPostUseCase.publish(
                ownerUserId = session.ownerUserId,
                content = content,
                imageUrls = imageUrls,
                visibility = visibility
            ).fold(
                onSuccess = { post ->
                    _uiState.update { current ->
                        current.copy(
                            posts = listOf(post) + current.posts.filterNot { it.id == post.id },
                            isPublishing = false,
                            composerText = "",
                            imageDrafts = emptyList(),
                            composerDraftRestored = false,
                            publishRevision = current.publishRevision + 1,
                            infoMessage = text(R.string.explore_publish_success)
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isPublishing = false,
                            errorMessage = error.message ?: text(R.string.explore_publish_failed)
                        )
                    }
                }
            )
        }
    }

    fun ExploreOrchestrator.onComposerTextChange(text: String) {
        val ownerUserId = draftOwnerId()
        draftRepository.saveComposerText(ownerUserId, text)
        _uiState.update { it.copy(composerText = text) }
    }

    fun ExploreOrchestrator.dismissComposerDraftHint() {
        _uiState.update { it.copy(composerDraftRestored = false) }
    }

    fun ExploreOrchestrator.clearComposer() {
        val ownerUserId = draftOwnerId()
        draftRepository.clearAllDrafts(ownerUserId)
        _uiState.update {
            it.copy(
                composerText = "",
                imageDrafts = emptyList(),
                composerDraftRestored = false
            )
        }
    }

    fun ExploreOrchestrator.onVisibilitySelected(visibility: String) {
        val normalized = ExploreDraftPolicy.normalizeVisibility(visibility)
        val ownerUserId = draftOwnerId()
        draftRepository.saveVisibility(ownerUserId, normalized)
        _uiState.update {
            it.copy(selectedVisibility = normalized, useDefaultPostVisibility = false)
        }
    }

    fun ExploreOrchestrator.addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val currentDrafts = _uiState.value.imageDrafts
        val maxImages = 9
        val available = maxImages - currentDrafts.size
        if (available <= 0) return
        val toAdd = uris.take(available)

        val newDrafts = toAdd.mapNotNull { uri ->
            val persistedUri = persistPickedImage(uri) ?: return@mapNotNull null
            PostImageDraft(uri = persistedUri, isUploading = false)
        }
        val updated = currentDrafts + newDrafts
        _uiState.update { it.copy(imageDrafts = updated) }
        newDrafts.forEach { draft ->
            uploadDraftImage(draft)
        }
    }

    @Suppress("Recycle") private fun ExploreOrchestrator.persistPickedImage(uri: Uri): Uri? { // 资源由 `?.use` 关闭；lint 的 Recycle 检测不识别安全调用形态（人工核实，与 #124 同式）
        return try {
            val dir = File(application.filesDir, "draft_images").apply { mkdirs() }
            val file = File(dir, "img_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}.jpg")
            application.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            Uri.fromFile(file)
        } catch (_: Exception) {
            null
        }
    }

    fun ExploreOrchestrator.retryDraftImage(draftId: String) {
        val draft = _uiState.value.imageDrafts.firstOrNull { it.id == draftId } ?: return
        uploadDraftImage(draft)
    }

    private fun ExploreOrchestrator.uploadDraftImage(draft: PostImageDraft) {
        val ownerUserId = draftOwnerId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) return
        updateDraft(draft.id) { it.copy(isUploading = true, errorMessage = null) }
        scope.launch(Dispatchers.IO) {
            try {
                val bytes = application.contentResolver.openInputStream(draft.uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        updateDraft(draft.id) { it.copy(isUploading = false, errorMessage = text(R.string.explore_upload_failed)) }
                    }
                    return@launch
                }
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                uploadQueue.enqueue(draft.id, ownerUserId, base64)
                val job = uploadQueue.startUpload(draft.id)
                job?.join()
                val item = uploadQueue.itemsFlow.value[draft.id]
                withContext(Dispatchers.Main) {
                    if (item?.status == UploadStatus.SUCCESS && item.uploadUrl != null) {
                        updateDraft(draft.id) { it.copy(uploadUrl = item.uploadUrl, isUploading = false, errorMessage = null) }
                    } else {
                        updateDraft(draft.id) { it.copy(isUploading = false, errorMessage = item?.errorMessage ?: text(R.string.explore_upload_failed)) }
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    updateDraft(draft.id) { it.copy(isUploading = false, errorMessage = text(R.string.explore_upload_failed)) }
                }
            }
        }
    }

    fun ExploreOrchestrator.removeImage(id: String) {
        val draft = _uiState.value.imageDrafts.firstOrNull { it.id == id }
        uploadQueue.cancel(id)
        if (draft?.uploadUrl != null) {
            val ownerUserId = draftOwnerId()
            if (com.maodouchat.session.CurrentSession.hasSession() && ownerUserId.isNotBlank()) {
                scope.launch {
                    uploadQueue.discardUploaded(ownerUserId = ownerUserId, url = draft.uploadUrl)
                }
            }
        }
        _uiState.update { state ->
            state.copy(imageDrafts = state.imageDrafts.filterNot { it.id == id })
        }
    }

    private fun ExploreOrchestrator.updateDraft(id: String, transform: (PostImageDraft) -> PostImageDraft) {
        _uiState.update { state ->
            state.copy(imageDrafts = state.imageDrafts.map { if (it.id == id) transform(it) else it })
        }
    }
