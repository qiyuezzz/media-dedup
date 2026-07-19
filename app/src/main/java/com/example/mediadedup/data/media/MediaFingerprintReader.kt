package com.example.mediadedup.data.media

import android.net.Uri

/**
 * Reads perceptual-hash input pixels from media files. Implementations must:
 *   - decode a small thumbnail / representative frame,
 *   - correct EXIF orientation for images,
 *   - release [android.graphics.Bitmap] in a `finally` block,
 *   - swallow per-file exceptions and return `null` so a single bad file never
 *     aborts the whole scan.
 *
 * Both images and videos are decoded via [android.content.ContentResolver.loadThumbnail]
 * - for videos it returns MediaStore's baked poster frame, which is reliable and
 * fast. [android.media.MediaMetadataRetriever] is deliberately avoided for
 * videos: its `getFrameAtTime` native call can stall on HEVC/corrupt files and
 * ignores coroutine cancellation, which previously froze the scan.
 *
 * Returned bitmaps are normalized to a [PerceptualHash input][com.example.mediadedup.util.PerceptualHash]
 * sized [THUMBNAIL_SIZE]x[THUMBNAIL_SIZE] grayscale [IntArray].
 */
interface MediaFingerprintReader {

    /**
     * Decode [uri] (an image) to a 32x32 grayscale matrix suitable for pHash.
     * Returns null if the image cannot be decoded.
     */
    suspend fun calculateImageHash(uri: Uri): Long?

    /**
     * Decode [uri] (a video) to a 32x32 grayscale matrix suitable for pHash,
     * using MediaStore's poster frame via [android.content.ContentResolver.loadThumbnail].
     * [durationMs] is retained for API compatibility but no longer used to seek
     * to a mid-point frame. Returns null if the frame cannot be decoded.
     */
    suspend fun calculateVideoHash(uri: Uri, durationMs: Long?): Long?

    companion object {
        /** Side length of the thumbnail we decode before computing pHash. */
        const val THUMBNAIL_SIZE: Int = 64
    }
}
