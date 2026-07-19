package com.example.mediadedup.data.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.CancellationSignal
import android.util.Size
import com.example.mediadedup.util.PerceptualHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [MediaFingerprintReader].
 *
 * minSdk is 34, so [ContentResolver.loadThumbnail] (API 29+) is always available
 * - no need for the BitmapFactory inSampleSize fallback the spec mentions for
 * API 26-28. `loadThumbnail` also bakes in EXIF orientation for image providers,
 * so we do not re-apply it here.
 *
 * Both images AND videos use [ContentResolver.loadThumbnail]: for videos it
 * returns the poster/middle frame baked by MediaStore, which is far faster and
 * more reliable than [android.media.MediaMetadataRetriever.getFrameAtTime].
 * The retriever path was found to stall on some HEVC/corrupt files (native
 * `getFrameAtTime: videoFrame is a NULL pointer` errors) and could block a
 * whole IO thread ignoring coroutine cancellation, exhausting the bounded
 * Semaphore and freezing the scan. `loadThumbnail` is provider-managed and
 * returns quickly even for problematic files.
 *
 * All decoding runs on [Dispatchers.IO]. Every public method catches exceptions
 * per-file and returns null, so one corrupt file never breaks a scan.
 */
class AndroidMediaFingerprintReader(private val context: Context) : MediaFingerprintReader {

    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun calculateImageHash(uri: Uri): Long? = withContext(Dispatchers.IO) {
        val bitmap = loadThumbnailBitmap(uri) ?: return@withContext null
        try {
            computePerceptualHash(bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    override suspend fun calculateVideoHash(uri: Uri, durationMs: Long?): Long? = withContext(Dispatchers.IO) {
        // durationMs is no longer used to pick a mid-frame; loadThumbnail gives
        // us MediaStore's baked poster frame, which is good enough for a single-
        // frame pHash and is dramatically more reliable than MediaMetadataRetriever.
        val bitmap = loadThumbnailBitmap(uri) ?: return@withContext null
        try {
            computePerceptualHash(bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    // ---- internals ----

    private fun loadThumbnailBitmap(uri: Uri): Bitmap? = try {
        resolver.loadThumbnail(
            uri,
            Size(MediaFingerprintReader.THUMBNAIL_SIZE, MediaFingerprintReader.THUMBNAIL_SIZE),
            CancellationSignal()
        )
    } catch (_: Exception) {
        null
    }

    /**
     * Scale [source] to [PerceptualHash.PERCEPTUAL_HASH_SIZE] (32x32) allowing
     * aspect-ratio stretch, convert to grayscale via BT.601 luma, and feed into
     * [PerceptualHash.compute]. Recycles nothing - the caller owns the bitmap.
     */
    private fun computePerceptualHash(source: Bitmap): Long? {
        val target = PerceptualHash.PERCEPTUAL_HASH_SIZE
        val scaled = if (source.width == target && source.height == target) {
            source
        } else {
            try {
                Bitmap.createScaledBitmap(source, target, target, false)
            } catch (_: Exception) {
                return null
            }
        }
        val tryRecycleScaled = scaled !== source
        return try {
            val pixels = IntArray(target * target)
            scaled.getPixels(pixels, 0, target, 0, 0, target, target)
            val gray = IntArray(pixels.size)
            for (i in pixels.indices) {
                val c = pixels[i]
                // ITU-R BT.601 luma.
                val luma = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)).toInt()
                gray[i] = luma and 0xFF
            }
            PerceptualHash.compute(gray)
        } catch (_: Exception) {
            null
        } finally {
            if (tryRecycleScaled && !scaled.isRecycled) scaled.recycle()
        }
    }
}
