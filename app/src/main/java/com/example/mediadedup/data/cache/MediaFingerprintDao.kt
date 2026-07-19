package com.example.mediadedup.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data access for [MediaFingerprintEntity]. Reads are batched by media id so the
 * scanner can hydrate the cache for a whole scan set in one query, then only
 * recompute the missing/stale entries.
 */
@Dao
interface MediaFingerprintDao {

    /** Return all cached fingerprints for the given media ids, in one shot. */
    @Query(
        """
        SELECT * FROM media_fingerprints
        WHERE mediaId IN (:mediaIds)
        """
    )
    suspend fun loadByMediaIds(mediaIds: List<Long>): List<MediaFingerprintEntity>

    /**
     * Insert/replace a batch of fingerprints. Conflict policy REPLACE so the
     * same mediaId is always overwritten with the freshest analysis.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<MediaFingerprintEntity>)

    /** Remove cached rows whose mediaId no longer exists in MediaStore. */
    @Query("DELETE FROM media_fingerprints WHERE mediaId IN (:mediaIds)")
    suspend fun deleteByMediaIds(mediaIds: List<Long>)

    /** Wipe the table (used by Settings when the algorithm version bumps). */
    @Query("DELETE FROM media_fingerprints")
    suspend fun clear()
}
