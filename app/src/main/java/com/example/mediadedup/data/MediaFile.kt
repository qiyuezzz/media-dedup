package com.example.mediadedup.data

import android.net.Uri

/**
 * A single media item discovered via MediaStore.
 *
 * Runtime-only fields (`hash`, `perceptualHash`) are filled in lazily by the
 * scanning pipeline and are not part of the cache reuse key.
 */
data class MediaFile(
    val id: Long,
    /**
     * Content uri. Nullable so unit tests can construct [MediaFile] without
     * an Android [Uri] (which is not mocked on the JVM); production code always
     * supplies a non-null value.
     */
    val uri: Uri? = null,
    val name: String,
    val path: String,
    val size: Long,
    val mimeType: String,
    val dateAdded: Long,
    val type: MediaType,
    /** MD5 of the full file content, computed during the exact-duplicate pass. */
    var hash: String? = null,
    /** 64-bit perceptual hash, computed during the near-duplicate pass. */
    var perceptualHash: Long? = null,

    // ---- MediaStore metadata used for near-duplicate candidate filtering ----

    /** MediaStore IS_FAVORITE (1 = favorite). Used by keep-recommendation. */
    val isFavorite: Int = 0,
    /** Pixel width; 0 if unknown. Used for resolution ranking + aspect-ratio bucketing. */
    val width: Int = 0,
    /** Pixel height; 0 if unknown. Used for resolution ranking + aspect-ratio bucketing. */
    val height: Int = 0,
    /** Duration in ms for audio/video; 0 for images / unknown. */
    val durationMs: Long = 0L,
    /** MediaStore DATE_MODIFIED (epoch seconds). Part of the cache reuse key. */
    val modifiedAt: Long = 0L,
    /** True if the file lives under a camera source (DCIM/Camera). Best-effort. */
    val isCameraOriginal: Boolean = false,
    /** True if the file appears edited (e.g. name/path contains edit markers). Best-effort. */
    val isEdited: Boolean = false
)

enum class MediaType {
    IMAGE, VIDEO, AUDIO
}
