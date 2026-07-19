package com.example.mediadedup.data

data class MediaStats(
    val totalSize: Long = 0,
    val imageSize: Long = 0,
    val videoSize: Long = 0,
    val audioSize: Long = 0,
    val totalCount: Int = 0,
    val imageCount: Int = 0,
    val videoCount: Int = 0,
    val audioCount: Int = 0,
    val potentialSavings: Long = 0,
    val duplicateCount: Int = 0,

    // ---- Near-duplicate (pHash) stats ----

    /** Number of similar groups with more than one member. */
    val similarGroupCount: Int = 0,
    /** Total media items across all similar groups. */
    val similarMediaCount: Int = 0,
    /**
     * Estimated reclaimable bytes for similar content, based on the current
     * keep-recommendation (sum of non-recommended item sizes). Marked as
     * estimated in UI - not equivalent to [potentialSavings] which is exact.
     */
    val similarReclaimableBytes: Long = 0
)

