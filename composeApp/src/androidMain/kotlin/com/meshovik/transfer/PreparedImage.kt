// com.meshovik.transfer/ImagePreparer.kt
package com.meshovik.transfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import timber.log.Timber
import java.io.ByteArrayOutputStream
import androidx.core.graphics.scale
import coil3.Uri
import coil3.toAndroidUri

data class PreparedImage(
    val compressedBytes: ByteArray,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String = "image/jpeg",
    val width: Int,
    val height: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PreparedImage

        if (sizeBytes != other.sizeBytes) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (!compressedBytes.contentEquals(other.compressedBytes)) return false
        if (fileName != other.fileName) return false
        if (mimeType != other.mimeType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sizeBytes.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + compressedBytes.contentHashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}

object ImagePreparer {

    /**
     * Подготавливает изображение для отправки по BLE:
     * - Уменьшает размер
     * - Сжимает JPEG (lossy)
     */
    fun prepareImage(
        context: Context,
        uri: Uri,
        maxLongSide: Int = 1280,        // можно увеличить до 1920
        quality: Int = 82               // 75-85 — хороший баланс
    ): PreparedImage {
        val resolver = context.contentResolver

        // 1. Загружаем с subsampling
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        resolver.openInputStream(uri.toAndroidUri())?.use { BitmapFactory.decodeStream(it, null, options) }

        var inSampleSize = 1
        while (options.outWidth / inSampleSize > maxLongSide ||
            options.outHeight / inSampleSize > maxLongSide) {
            inSampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
        }

        val bitmap = resolver.openInputStream(uri.toAndroidUri())?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: throw IllegalStateException("Cannot decode image")

        // 2. Масштабируем, если нужно
        val scaledBitmap = if (bitmap.width > maxLongSide || bitmap.height > maxLongSide) {
            val scale = maxLongSide.toFloat() / maxOf(bitmap.width, bitmap.height)
            bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
        } else bitmap

        // 3. Сжимаем в JPEG
        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)

        val compressedBytes = outputStream.toByteArray()

        Timber.i("Image prepared: ${compressedBytes.size} bytes (${scaledBitmap.width}x${scaledBitmap.height}, quality=$quality)")

        scaledBitmap.recycle()
        if (bitmap != scaledBitmap) bitmap.recycle()

        return PreparedImage(
            compressedBytes = compressedBytes,
            fileName = "image_${System.currentTimeMillis()}.jpg",
            sizeBytes = compressedBytes.size.toLong(),
            width = scaledBitmap.width,
            height = scaledBitmap.height
        )
    }
}