package com.example.mediadedup.data.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted perceptual-hash fingerprint for a media item.
 *
 * Cache reuse (spec §8.2): a stored row is considered valid only when
 * [mediaId], [fileSize], [modifiedAt] AND [perceptualHashVersion] all match
 * the current media item. Any drift triggers a recompute.
 *
 * @param mediaId MediaStore _ID; primary key.
 * @param uri content uri string (for debugging only, not part of the reuse key).
 * @param fileSize bytes; reuse key component.
 * @param modifiedAt MediaStore DATE_MODIFIED; reuse key component.
 * @param exactHash MD5 if computed; kept here so the cache can also answer
 *   exact-duplicate queries, but not required.
 * @param perceptualHash 64-bit pHash, null if analysis failed.
 * @param perceptualHashVersion algorithm version, reuse key component.
 * @param analyzedAt epoch millis when this row was written.
 */
@Entity(tableName = "media_fingerprints")
data class MediaFingerprintEntity(
    @PrimaryKey
    val mediaId: Long,
    val uri: String,
    val fileSize: Long,
    val modifiedAt: Long,
    val exactHash: String?,
    val perceptualHash: Long?,
    val perceptualHashVersion: Int?,
    val analyzedAt: Long
)
