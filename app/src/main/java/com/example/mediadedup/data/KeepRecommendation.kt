package com.example.mediadedup.data

/**
 * One reason a media item is recommended to be *kept* (i.e. not deleted) within
 * a similar group. UI maps these to localized hint strings.
 *
 * "Most clear" / "best quality" wording is deliberately NOT used - file size is
 * only a proxy. See spec §12.
 */
enum class KeepReason {
    /** Marked as favorite in MediaStore (IS_FAVORITE). */
    FAVORITE,

    /** Filename/path suggests an edited export (e.g. contains "EDIT", "crop"). */
    EDITED,

    /** Higher pixel resolution than siblings. */
    HIGHER_RESOLUTION,

    /** Larger file size than siblings (secondary tiebreaker). */
    LARGER_FILE,

    /** Lives under DCIM/Camera, treated as the original capture. */
    CAMERA_ORIGINAL,

    /** Earliest creation time among siblings. */
    EARLIER_CREATED
}

/**
 * Recommendation to keep [mediaId] within its similar group, with the [reasons]
 * that justified the choice. A group always has exactly one recommendation.
 */
data class KeepRecommendation(
    val mediaId: Long,
    val reasons: List<KeepReason>
)
