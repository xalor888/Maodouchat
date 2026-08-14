package com.maodouchat.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * 图片选择与压缩工具
 */
object ImagePicker {

    /**
     * 将 Uri 图片转为 Base64 字符串（压缩后）
     *
     * @param context Context
     * @param uri 图片 Uri
     * @param maxWidth 最长边上限（默认 800px，保留参数名以兼容现有调用）
     * @param quality 压缩质量（默认 70）
     * @return Base64 编码字符串
     */
    fun uriToBase64(context: Context, uri: Uri, maxWidth: Int = 800, quality: Int = 70): String? {
        val bytes = compressedImageBytes(context, uri, maxWidth, quality) ?: return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun compressToFile(
        context: Context,
        uri: Uri,
        target: File,
        maxWidth: Int = 1_600,
        quality: Int = 82
    ): File? {
        val bytes = compressedImageBytes(context, uri, maxWidth, quality) ?: return null
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            target
        }.onFailure { Log.w(TAG, "Failed to persist compressed image", it) }.getOrNull()
    }

    private fun compressedImageBytes(context: Context, uri: Uri, maxWidth: Int, quality: Int): ByteArray? {
        var originalBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        return try {
            // 文件大小预检：超过 15 MB 直接拒绝，避免后续整图 base64 导致 OOM。
            val fileSize = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
            if (fileSize > MAX_IMAGE_BYTES) {
                Log.w(TAG, "Image too large: $fileSize bytes (max $MAX_IMAGE_BYTES)")
                return null
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val orientation = context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL

            val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxWidth, MAX_IMAGE_PIXELS)
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decodedBitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            } ?: return null
            originalBitmap = decodedBitmap
            val orientedBitmap = applyExifOrientation(decodedBitmap, orientation)
            if (orientedBitmap !== decodedBitmap) decodedBitmap.recycle()
            originalBitmap = orientedBitmap

            // 按最长边等比缩放，避免极窄竖图仍保留超大高度。
            val longestSide = maxOf(originalBitmap.width, originalBitmap.height)
            scaledBitmap = if (longestSide > maxWidth) {
                val ratio = maxWidth.toFloat() / longestSide
                val newWidth = (originalBitmap.width * ratio).toInt().coerceAtLeast(1)
                val newHeight = (originalBitmap.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)
            } else {
                originalBitmap
            }

            // 压缩为 JPEG
            val outputStream = java.io.ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            var bytes = outputStream.toByteArray()
            if (bytes.size > MAX_COMPRESSED_IMAGE_BYTES) {
                outputStream.reset()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, minOf(quality, 52), outputStream)
                bytes = outputStream.toByteArray()
            }
            if (bytes.size > MAX_COMPRESSED_IMAGE_BYTES) return null

            bytes
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Selected image exhausted available memory", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read selected image", e)
            null
        } finally {
            // 异常路径也释放 bitmap，避免内存泄漏
            if (scaledBitmap !== originalBitmap) scaledBitmap?.recycle()
            originalBitmap?.recycle()
        }
    }

    private const val TAG = "ImagePicker"
    private const val MAX_IMAGE_BYTES = 15L * 1024L * 1024L
    private const val MAX_IMAGE_PIXELS = 4_000_000
    private const val MAX_COMPRESSED_IMAGE_BYTES = 1_200_000

    private fun calculateInSampleSize(width: Int, height: Int, maxWidth: Int, maxPixels: Int): Int {
        var sampleSize = 1
        val safeMaxWidth = maxWidth.coerceAtLeast(1)
        val safeMaxPixels = maxPixels.coerceAtLeast(1).toLong()
        while (true) {
            val sampledWidth = (width / sampleSize).coerceAtLeast(1)
            val sampledHeight = (height / sampleSize).coerceAtLeast(1)
            if (maxOf(sampledWidth, sampledHeight) <= safeMaxWidth &&
                sampledWidth.toLong() * sampledHeight.toLong() <= safeMaxPixels
            ) {
                return sampleSize
            }
            if (sampleSize > Int.MAX_VALUE / 2) return Int.MAX_VALUE
            sampleSize *= 2
        }
    }

    private fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Base64 解码为 Bitmap
     */
    fun base64ToBitmap(base64: String): Bitmap? {
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            // 损坏的 Base64 或 OOM — 必须 Log 以区分"图片损坏"和"内容空"
            Log.w(TAG, "base64ToBitmap decode failed", e)
            null
        }
    }
}
