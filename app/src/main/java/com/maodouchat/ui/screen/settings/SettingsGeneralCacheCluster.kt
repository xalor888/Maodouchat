package com.maodouchat.ui.screen.settings

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.attachment.AttachmentTransferCoordinator
import com.maodouchat.util.MediaCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(coil.annotation.ExperimentalCoilApi::class)
internal fun GeneralSettingsViewModel.clearCache() {
    if (clearCacheJob?.isActive == true) return
    cacheRefreshGeneration++
    cacheRefreshJob?.cancel()
    val generation = cacheRefreshGeneration
    val job = viewModelScope.launch {
        val context = getApplication<Application>()
        try {
            val removedBytes = withContext(Dispatchers.IO) {
                val before = MediaCache.currentCacheBytes(context)
                AttachmentTransferCoordinator.deleteAll(context)
                MediaCache.cleanupReturningBytes(context)
                com.maodouchat.util.LinkPreviewRepository.clear()
                // 1.35：清除 Coil 图片磁盘缓存（内存缓存在低内存时已清，这里补磁盘）
                runCatching { coil.Coil.imageLoader(context).diskCache?.clear() }
                (before - MediaCache.currentCacheBytes(context)).coerceAtLeast(0L)
            }
            if (cacheRefreshGeneration != generation) return@launch
            val text = formatSize(removedBytes)
            _uiState.update {
                it.copy(
                    cacheSizeText = text,
                    infoMessage = context.getString(R.string.general_cache_cleared, text),
                )
            }
            refreshCacheSize()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (cacheRefreshGeneration == generation) {
                _uiState.update {
                    it.copy(infoMessage = error.message ?: text(R.string.error_operation_failed))
                }
            }
        }
    }
    clearCacheJob = job
    job.invokeOnCompletion {
        if (clearCacheJob === job) clearCacheJob = null
    }
}

internal fun GeneralSettingsViewModel.showComingSoon() { _uiState.update { it.copy(infoMessage = getApplication<Application>().getString(R.string.general_about_summary)) } }

internal fun GeneralSettingsViewModel.consumeInfoMessage() {
    if (_uiState.value.infoMessage != null) {
        _uiState.update { it.copy(infoMessage = null) }
    }
}

internal fun GeneralSettingsViewModel.refreshCacheSize() {
    val generation = ++cacheRefreshGeneration
    cacheRefreshJob?.cancel()
    val job = viewModelScope.launch {
        val context = getApplication<Application>()
        try {
            val bytes = withContext(Dispatchers.IO) { MediaCache.currentCacheBytes(context) }
            if (cacheRefreshGeneration == generation) {
                _uiState.update { it.copy(cacheSizeText = formatSize(bytes)) }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (cacheRefreshGeneration == generation) {
                _uiState.update {
                    it.copy(infoMessage = error.message ?: text(R.string.error_operation_failed))
                }
            }
        }
    }
    cacheRefreshJob = job
    job.invokeOnCompletion {
        if (cacheRefreshJob === job) cacheRefreshJob = null
    }
}

internal fun GeneralSettingsViewModel.formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024L * 1024) return "${bytes / 1024} KB"
    if (bytes < 1024L * 1024 * 1024) return "${bytes / (1024L * 1024)} MB"
    return "${bytes / (1024L * 1024 * 1024)} GB"
}
