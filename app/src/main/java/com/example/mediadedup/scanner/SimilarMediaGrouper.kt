package com.example.mediadedup.scanner

import com.example.mediadedup.data.KeepRecommendation
import com.example.mediadedup.data.KeepReason
import com.example.mediadedup.data.MediaFile
import com.example.mediadedup.data.MediaType
import com.example.mediadedup.data.SimilarMediaGroup
import com.example.mediadedup.data.SimilarMediaItem
import com.example.mediadedup.util.PerceptualHash
import kotlin.math.roundToInt

/**
 * Pure-Kotlin near-duplicate grouping. No Android, no I/O - everything operates
 * on already-hashed [MediaFile] lists, which makes it fully unit-testable.
 *
 * Pipeline (spec §9-§11):
 *   1. Collapse exact (MD5) groups to a single representative so identical
 *      files don't surface twice.
 *   2. Bucket remaining candidates by media type + orientation + aspect-ratio
 *      (images) or aspect-ratio + duration (videos) to avoid global O(n²).
 *   3. Within each bucket (+ neighbours), greedily assign each candidate to the
 *      first existing group whose representative is within the pHash threshold.
 *      No transitive Union-Find: A~B and B~C does NOT imply A~C.
 *   4. Keep only groups with >1 member.
 *   5. Compute a keep-recommendation for each group.
 *
 * Stability: candidates are sorted by (favorite desc, pixel count desc, size
 * desc, id asc) so the same input always yields the same grouping.
 */
object SimilarMediaGrouper {

    /** Aspect-ratio bucket granularity: ratio * 20, rounded. */
    private const val ASPECT_RATIO_BUCKETS_PER_UNIT = 20

    /** Duration bucket size in ms (2s). */
    private const val DURATION_BUCKET_MS = 2_000L

    /**
     * @param allFiles every file from the scan (must already carry MD5 [MediaFile.hash]
     *   where computable, and pHash [MediaFile.perceptualHash] for IMAGE/VIDEO).
     * @return similar groups with >1 member, images and videos never mixed.
     */
    fun group(allFiles: List<MediaFile>): List<SimilarMediaGroup> {
        // 1. Collapse exact-duplicate groups to one representative each.
        val candidates = collapseExactGroups(allFiles)
            .filter { it.perceptualHash != null && (it.type == MediaType.IMAGE || it.type == MediaType.VIDEO) }

        if (candidates.size < 2) return emptyList()

        // 2. Bucket + 3. greedy cluster, per media type.
        val imageGroups = clusterByBucket(candidates.filter { it.type == MediaType.IMAGE }, isVideo = false)
        val videoGroups = clusterByBucket(candidates.filter { it.type == MediaType.VIDEO }, isVideo = true)

        // 4. filter size>1 + 5. keep-recommendation.
        return (imageGroups + videoGroups)
            .filter { it.items.size > 1 }
            .map { group -> withKeepRecommendation(group) }
    }

    // ---- Step 1: collapse exact (MD5) groups ----

    /**
     * For each set of files sharing the same non-null MD5, keep only the
     * "best" one (per representative rule) and drop the rest from the
     * near-duplicate candidate pool. Files with null hash pass through.
     */
    internal fun collapseExactGroups(files: List<MediaFile>): List<MediaFile> {
        val byHash = files.filter { it.hash != null }.groupBy { it.hash!! }
        val collapsedIds = mutableSetOf<Long>()
        val representatives = mutableListOf<MediaFile>()
        for ((_, group) in byHash) {
            if (group.size <= 1) continue
            val rep = pickExactGroupRepresentative(group)
            representatives += rep
            collapsedIds += group.map { it.id } - rep.id
        }
        // Keep all files except the collapsed (non-representative) ones.
        return files.filter { it.id !in collapsedIds }
    }

    /** Representative preference: favorite, then pixels, then size, then earlier added. */
    private fun pickExactGroupRepresentative(group: List<MediaFile>): MediaFile {
        return group.sortedWith(
            compareByDescending<MediaFile> { it.isFavorite }
                .thenByDescending { it.width.toLong() * it.height }
                .thenByDescending { it.size }
                .thenBy { it.dateAdded }
        ).first()
    }

    // ---- Step 2 + 3: bucketing + greedy clustering ----

    private fun clusterByBucket(candidates: List<MediaFile>, isVideo: Boolean): List<SimilarMediaGroup> {
        if (candidates.size < 2) return emptyList()

        val sorted = candidates.sortedWith(candidateComparator())
        val buckets = LinkedHashMap<BucketKey, MutableList<MediaFile>>()
        for (file in sorted) {
            val key = bucketKey(file, isVideo) ?: continue
            buckets.getOrPut(key) { mutableListOf() } += file
        }

        // Index of every group keyed by its representative's id, plus a fast
        // set of every representative id currently living in a neighbour bucket.
        // Without these, assignToGroup's inner scan was O(G * neighbours) per
        // candidate -> O(N^2..N^3) overall and froze the Main thread on 11k files.
        val groupsByRepId = mutableMapOf<Long, MutableSimilarGroup>()
        val groups = mutableListOf<MutableSimilarGroup>()
        var groupId = 0
        for ((key, members) in buckets) {
            // Reps from this bucket and its neighbours: precompute as a Set for O(1) lookup.
            // LinkedHashSet keeps iteration order stable (sorted order within each
            // bucket) so the greedy "first match wins" assignment is reproducible.
            val neighbourRepIds = LinkedHashSet<Long>()
            for (nKey in neighbourBuckets(key, isVideo)) {
                buckets[nKey]?.forEach { neighbourRepIds += it.id }
            }
            for (file in members) {
                val target = assignToGroup(file, groupsByRepId, neighbourRepIds)
                if (target == null) {
                    val mg = MutableSimilarGroup(id = "g${groupId++}", representative = file, items = mutableListOf(file))
                    groups += mg
                    groupsByRepId[file.id] = mg
                }
            }
        }

        return groups.map { mg ->
            val items = mg.items.map { f ->
                SimilarMediaItem(
                    file = f,
                    distanceToRepresentative = PerceptualHash.hammingDistance(
                        mg.representative.perceptualHash!!,
                        f.perceptualHash!!
                    )
                )
            }
            SimilarMediaGroup(
                id = mg.id,
                mediaType = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
                representativeId = mg.representative.id,
                items = items,
                maxDistance = items.maxOf { it.distanceToRepresentative },
                keepRecommendation = KeepRecommendation(mg.representative.id, emptyList()) // filled later
            )
        }
    }

    /**
     * O(neighbourRepIds) lookup instead of O(all groups). We only ever compare
     * the candidate against groups whose representative lives in a neighbour
     * bucket - and we iterate those groups in insertion order so the first
     * match wins, preserving the original greedy assignment semantics.
     */
    private fun assignToGroup(
        file: MediaFile,
        groupsByRepId: Map<Long, MutableSimilarGroup>,
        neighbourRepIds: Set<Long>
    ): MutableSimilarGroup? {
        if (file.perceptualHash == null) return null
        // Iterate neighbour reps in their original insertion order (the order
        // they appear in groupsByRepId.values) so the first match wins, same
        // as the old linear scan over `groups`.
        for (repId in neighbourRepIds) {
            val group = groupsByRepId[repId] ?: continue
            if (PerceptualHash.isSimilar(group.representative.perceptualHash!!, file.perceptualHash!!)) {
                group.items += file
                return group
            }
        }
        return null
    }

    private fun candidateComparator(): Comparator<MediaFile> =
        compareByDescending<MediaFile> { it.isFavorite }
            .thenByDescending { it.width.toLong() * it.height }
            .thenByDescending { it.size }
            .thenBy { it.id }

    /** Bucket key, or null if the file lacks dimensions. */
    private fun bucketKey(file: MediaFile, isVideo: Boolean): BucketKey? {
        if (file.width <= 0 || file.height <= 0) return null
        val orientation = if (file.width >= file.height) Orientation.LANDSCAPE else Orientation.PORTRAIT
        val ratio = file.width.toDouble() / file.height
        val ratioBucket = (ratio * ASPECT_RATIO_BUCKETS_PER_UNIT).roundToInt()
        return if (isVideo) {
            BucketKey(orientation, ratioBucket, (file.durationMs / DURATION_BUCKET_MS).toInt())
        } else {
            BucketKey(orientation, ratioBucket, durationBucket = null)
        }
    }

    /**
     * Neighbour buckets to also scan: same orientation, ratioBucket +/-1, and
     * (for video) durationBucket +/-1.
     */
    private fun neighbourBuckets(key: BucketKey, isVideo: Boolean): List<BucketKey> {
        val keys = mutableListOf(key)
        for (dr in -1..1) {
            keys += key.copy(aspectBucket = key.aspectBucket + dr)
        }
        if (isVideo && key.durationBucket != null) {
            val d = key.durationBucket
            for (dd in -1..1) {
                keys += key.copy(durationBucket = d + dd)
            }
        }
        return keys.distinct()
    }

    // ---- Step 5: keep-recommendation ----

    private fun withKeepRecommendation(group: SimilarMediaGroup): SimilarMediaGroup {
        // Best = favorite, then most pixels, then largest, then camera-original,
        // then earliest created. We pick the *maximum* under this comparator,
        // so "higher is better" fields use ascending order (max picks them) and
        // "lower is better" (earliest date) uses descending.
        val best = group.items.maxWithOrNull(
            compareBy<SimilarMediaItem> { it.file.isFavorite }
                .thenBy { it.file.width.toLong() * it.file.height }
                .thenBy { it.file.size }
                .thenBy { it.file.isCameraOriginal }
                .thenByDescending { it.file.dateAdded }
        ) ?: group.items.first()

        val reasons = mutableListOf<KeepReason>()
        if (best.file.isFavorite > 0) reasons += KeepReason.FAVORITE
        if (best.file.isEdited) reasons += KeepReason.EDITED
        if (group.items.any { (it.file.width.toLong() * it.file.height) < (best.file.width.toLong() * best.file.height) }) {
            reasons += KeepReason.HIGHER_RESOLUTION
        }
        if (group.items.any { it.file.size < best.file.size }) reasons += KeepReason.LARGER_FILE
        if (best.file.isCameraOriginal) reasons += KeepReason.CAMERA_ORIGINAL
        val earliest = group.items.minByOrNull { it.file.dateAdded }
        if (earliest != null && earliest.file.id == best.file.id) reasons += KeepReason.EARLIER_CREATED
        if (reasons.isEmpty()) reasons += KeepReason.LARGER_FILE

        return group.copy(keepRecommendation = KeepRecommendation(best.file.id, reasons))
    }

    // ---- helpers ----

    private enum class Orientation { LANDSCAPE, PORTRAIT }

    private data class BucketKey(
        val orientation: Orientation,
        val aspectBucket: Int,
        val durationBucket: Int?
    )

    private class MutableSimilarGroup(
        val id: String,
        val representative: MediaFile,
        val items: MutableList<MediaFile>
    )

    /**
     * Recompute keep-recommendation for a group after deletion. Used by
     * [ScannerViewModel.onDeletionComplete] when the previous recommendation
     * pointed at a deleted file.
     */
    fun recomputeRecommendation(group: SimilarMediaGroup): SimilarMediaGroup =
        withKeepRecommendation(group)
}
