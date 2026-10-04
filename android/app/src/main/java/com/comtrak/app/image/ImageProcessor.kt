package com.comtrak.app.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

data class ProcessedImage(
    val id: String,
    val file: File,
    val width: Int,
    val height: Int,
    val fileSizeBytes: Long
)

class ImageProcessor(private val context: Context) {

    private val mediaDir: File by lazy {
        File(context.filesDir, "media").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Downscales, auto-rotates, and compresses an image from Uri to WebP format.
     */
    suspend fun processAndCompress(sourceUri: Uri, maxDimension: Int = 1920, quality: Int = 80): Result<ProcessedImage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val mediaId = UUID.randomUUID().toString()
                val outputFile = File(mediaDir, "$mediaId.webp")

                // 1. Determine orientation from Exif
                var orientation = ExifInterface.ORIENTATION_NORMAL
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    val exif = ExifInterface(input)
                    orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }

                // 2. Decode bounds to compute subsample ratio
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                }

                val origWidth = options.outWidth
                val origHeight = options.outHeight

                var inSampleSize = 1
                if (origHeight > maxDimension || origWidth > maxDimension) {
                    val halfHeight = origHeight / 2
                    val halfWidth = origWidth / 2
                    while ((halfHeight / inSampleSize) >= maxDimension && (halfWidth / inSampleSize) >= maxDimension) {
                        inSampleSize *= 2
                    }
                }

                // 3. Decode scaled Bitmap
                val decodeOptions = BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                }
                val rawBitmap = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, decodeOptions)
                } ?: throw IllegalStateException("Could not decode image from URI: $sourceUri")

                // 4. Rotate if needed based on Exif
                val matrix = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                }

                val rotatedBitmap = if (!matrix.isIdentity) {
                    Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true).also {
                        if (it != rawBitmap) rawBitmap.recycle()
                    }
                } else {
                    rawBitmap
                }

                // 5. Compress to WebP Lossy
                FileOutputStream(outputFile).use { outStream ->
                    @Suppress("DEPRECATION")
                    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        Bitmap.CompressFormat.WEBP
                    }
                    rotatedBitmap.compress(format, quality, outStream)
                    outStream.flush()
                }

                val finalWidth = rotatedBitmap.width
                val finalHeight = rotatedBitmap.height
                rotatedBitmap.recycle()

                ProcessedImage(
                    id = mediaId,
                    file = outputFile,
                    width = finalWidth,
                    height = finalHeight,
                    fileSizeBytes = outputFile.length()
                )
            }
        }
}
