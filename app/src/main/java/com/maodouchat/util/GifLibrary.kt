package com.maodouchat.util

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * 扫描本机 MediaStore 中的 GIF（只读，不上传）。
 */
object GifLibrary {
    fun queryLocalGifs(context: Context, limit: Int = 200): List<LocalGifItem> {
        val resolver = context.applicationContext.contentResolver
        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE
        )
        val selection = "${MediaStore.Images.Media.MIME_TYPE}=?"
        val selectionArgs = arrayOf("image/gif")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val out = ArrayList<LocalGifItem>(64)
        runCatching {
            resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext() && out.size < limit) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol)?.takeIf { it.isNotBlank() } ?: "gif_$id"
                    val size = cursor.getLong(sizeCol).coerceAtLeast(0L)
                    val date = cursor.getLong(dateCol).coerceAtLeast(0L)
                    val uri = ContentUris.withAppendedId(collection, id)
                    out += LocalGifItem(
                        id = id.toString(),
                        uriString = uri.toString(),
                        displayName = name,
                        sizeBytes = size,
                        dateAddedSec = date
                    )
                }
            }
        }
        return out
    }
}
