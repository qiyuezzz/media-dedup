package com.example.mediadedup.data

/**
 * One member of a [SimilarMediaGroup]. Carries the underlying [file] plus the
 * hamming [distanceToRepresentative] and whether the UI currently flags it for
 * removal. Selection state lives here so the group is self-describing for the
 * results screen; deletion still goes through the shared selected-id set on
 * [com.example.mediadedup.scanner.ScannerViewModel].
 */
data class SimilarMediaItem(
    val file: MediaFile,
    val distanceToRepresentative: Int,
    val selectedForRemoval: Boolean = false
)

/**
 * A group of visually similar media items produced by the pHash pipeline.
 *
 * The [representativeId] is the item the group was built around; it is also the
 * default keep-recommendation unless [keepRecommendation] overrides it (e.g.
 * when another member is a favorite). [maxDistance] is the largest
 * [SimilarMediaItem.distanceToRepresentative] in the group, surfaced in the UI
 * as "max distance N".
 */
data class SimilarMediaGroup(
    val id: String,
    val mediaType: MediaType,
    val representativeId: Long,
    val items: List<SimilarMediaItem>,
    val maxDistance: Int,
    val keepRecommendation: KeepRecommendation
) {
    /** Convenience: total bytes of non-recommended items = reclaimable estimate. */
    val reclaimableBytes: Long
        get() = items.sumOf { item ->
            if (item.file.id == keepRecommendation.mediaId) 0L else item.file.size
        }
}
