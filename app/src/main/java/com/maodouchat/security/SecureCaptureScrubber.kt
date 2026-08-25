package com.maodouchat.security

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * FLAG_SECURE 被 OEM 忽略时，系统仍可能把截图/录屏写进相册。
 * 全局防截屏或密聊开启期间，立刻删掉刚写入的捕获文件。
 * 挡不住外置相机。
 */
object SecureCaptureScrubber {
    private const val TAG = "SecureCaptureScrubber"
    private const val IMAGE_MAX_AGE_SEC = 8L
    private const val VIDEO_MAX_AGE_SEC = 12L

    fun deleteLatestCapture(context: Context) {
        runCatching { deleteLatestImage(context) }
            .onFailure { Log.w(TAG, "delete image capture failed", it) }
        runCatching { deleteLatestVideo(context) }
            .onFailure { Log.w(TAG, "delete video capture failed", it) }
    }

    fun shouldDeleteImage(path: String, name: String, ageSec: Long): Boolean =
        ageSec in 0..IMAGE_MAX_AGE_SEC && ScreenshotDetector.isScreenshotPath(path, name)

    fun shouldDeleteVideo(path: String, name: String, ageSec: Long): Boolean =
        ageSec in 0..VIDEO_MAX_AGE_SEC && ScreenshotDetector.isScreenRecordPath(path, name)

    private fun deleteLatestImage(context: Context) {
        val nowSec = System.currentTimeMillis() / 1000L
        queryLatest(
            context = context,
            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            idColumn = MediaStore.Images.Media._ID,
            nameColumn = MediaStore.Images.Media.DISPLAY_NAME,
            dateColumn = MediaStore.Images.Media.DATE_ADDED,
            pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.RELATIVE_PATH
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.DATA
            },
        )?.let { hit ->
            if (shouldDeleteImage(hit.path, hit.name, nowSec - hit.dateAdded)) {
                deleteUri(context, hit.uri)
            }
        }
    }

    private fun deleteLatestVideo(context: Context) {
        val nowSec = System.currentTimeMillis() / 1000L
        queryLatest(
            context = context,
            uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            idColumn = MediaStore.Video.Media._ID,
            nameColumn = MediaStore.Video.Media.DISPLAY_NAME,
            dateColumn = MediaStore.Video.Media.DATE_ADDED,
            pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.RELATIVE_PATH
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Media.DATA
            },
        )?.let { hit ->
            if (shouldDeleteVideo(hit.path, hit.name, nowSec - hit.dateAdded)) {
                deleteUri(context, hit.uri)
            }
        }
    }

    private data class Hit(
        val uri: Uri,
        val name: String,
        val path: String,
        val dateAdded: Long,
    )

    private fun queryLatest(
        context: Context,
        uri: Uri,
        idColumn: String,
        nameColumn: String,
        dateColumn: String,
        pathColumn: String,
    ): Hit? {
        val projection = arrayOf(idColumn, nameColumn, dateColumn, pathColumn)
        return context.contentResolver.query(uri, projection, null, null, "$dateColumn DESC")
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(idColumn))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(nameColumn)).orEmpty()
                val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(dateColumn))
                val pathIdx = cursor.getColumnIndex(pathColumn)
                val path = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else ""
                Hit(
                    uri = ContentUris.withAppendedId(uri, id),
                    name = name,
                    path = path,
                    dateAdded = dateAdded,
                )
            }
    }

    private fun deleteUri(context: Context, uri: Uri) {
        val deleted = context.contentResolver.delete(uri, null, null)
        Log.i(TAG, "scrubbed capture uri=$uri deleted=$deleted")
    }
}
