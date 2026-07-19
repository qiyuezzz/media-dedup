package com.example.mediadedup.scanner

import com.example.mediadedup.data.KeepReason
import com.example.mediadedup.data.MediaFile
import com.example.mediadedup.data.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [SimilarMediaGrouper]. Builds in-memory [MediaFile] lists
 * with hand-crafted pHashes to exercise clustering, bucketing, exact-group
 * collapse and keep-recommendation without touching Android or Bitmaps.
 */
class SimilarMediaGrouperTest {

    private fun file(
        id: Long,
        type: MediaType = MediaType.IMAGE,
        hash: String? = null,
        perceptualHash: Long? = null,
        size: Long = 1000L,
        width: Int = 1080,
        height: Int = 1920,
        durationMs: Long = 0L,
        isFavorite: Int = 0,
        dateAdded: Long = id,
        path: String = "/sdcard/DCIM/Camera/img$id.jpg",
        name: String = "img$id.jpg"
    ): MediaFile = MediaFile(
        id = id,
        // android.net.Uri.parse throws on the JVM (not mocked). Uri.EMPTY is a
        // static field access that works, and the grouper never inspects the uri.
        uri = android.net.Uri.EMPTY,
        name = name,
        path = path,
        size = size,
        mimeType = if (type == MediaType.VIDEO) "video/mp4" else "image/jpeg",
        dateAdded = dateAdded,
        type = type,
        hash = hash,
        perceptualHash = perceptualHash,
        isFavorite = isFavorite,
        width = width,
        height = height,
        durationMs = durationMs,
        modifiedAt = 0L,
        isCameraOriginal = path.contains("/DCIM/Camera", ignoreCase = true),
        isEdited = false
    )

    @Test
    fun emptyAndSingleCandidateProduceNoGroups() {
        assertTrue(SimilarMediaGrouper.group(emptyList()).isEmpty())
        assertTrue(SimilarMediaGrouper.group(listOf(file(1, perceptualHash = 0x01))).isEmpty())
    }

    @Test
    fun identicalHashesClusterIntoOneGroup() {
        val files = listOf(
            file(1, perceptualHash = 0b1010_1010L),
            file(2, perceptualHash = 0b1010_1010L),
            file(3, perceptualHash = 0b1010_1010L)
        )
        val groups = SimilarMediaGrouper.group(files)
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].items.size)
        // Representative is the one with highest pixel/size ranking; all equal -> smallest id.
        assertEquals(1L, groups[0].representativeId)
    }

    @Test
    fun distanceExceedingThresholdDoesNotGroup() {
        // 9 bits differ -> beyond threshold of 8.
        val a = 0L
        val b = 0b1_1111_1111L // 9 bits set
        val files = listOf(
            file(1, perceptualHash = a),
            file(2, perceptualHash = b)
        )
        assertTrue(SimilarMediaGrouper.group(files).isEmpty())
    }

    @Test
    fun noTransitiveUnionFindChaining() {
        // A is the group representative (largest size sorts first).
        //   A~B distance 4  -> B joins A's group.
        //   B~C distance 8  -> would link under transitive Union-Find.
        //   A~C distance 9  -> exceeds threshold, so C must NOT join A's group
        //                      under representative-based clustering.
        val a = 0b000_0000_0000L
        val b = 0b000_0000_1111L          // 4 bits differ from a
        val c = 0b11_1111_1110L           // 9 bits differ from a, 8 bits differ from b
        val files = listOf(
            file(1, perceptualHash = a, size = 3000L),
            file(2, perceptualHash = b, size = 2000L),
            file(3, perceptualHash = c, size = 1000L)
        )
        val groups = SimilarMediaGrouper.group(files)
        // C (id 3) must never be grouped with A (id 1) - that is the whole point
        // of avoiding transitive chaining.
        val groupWithA = groups.firstOrNull { g -> g.items.any { it.file.id == 1L } }
        val groupWithC = groups.firstOrNull { g -> g.items.any { it.file.id == 3L } }
        // If A has a group, C must not be in it.
        if (groupWithA != null) {
            assertFalse(
                "C must not join A's group via transitive chaining through B",
                groupWithA.items.any { it.file.id == 3L }
            )
        }
        // If both A and C each lead a group, they must be distinct groups.
        if (groupWithA != null && groupWithC != null) {
            assertFalse("A and C must not share a group", groupWithA.id == groupWithC.id)
        }
    }

    @Test
    fun imagesAndVideosNeverMixed() {
        val files = listOf(
            file(1, type = MediaType.IMAGE, perceptualHash = 0xABCD, durationMs = 0),
            file(2, type = MediaType.VIDEO, perceptualHash = 0xABCD, durationMs = 4000, width = 1920, height = 1080)
        )
        val groups = SimilarMediaGrouper.group(files)
        // Identical pHash but different types -> must be 0 groups (each type alone, size<2 within bucket).
        // Even if 2 groups formed, none mixes types.
        groups.forEach { g ->
            val types = g.items.map { it.file.type }.toSet()
            assertEquals(1, types.size)
        }
    }

    @Test
    fun aspectRatioMismatchPreventsGrouping() {
        // Same pHash but wildly different aspect ratios land in different buckets.
        val files = listOf(
            file(1, perceptualHash = 0x1234, width = 1080, height = 1920), // portrait 0.5625
            file(2, perceptualHash = 0x1234, width = 4000, height = 1000)  // landscape 4.0
        )
        assertTrue(SimilarMediaGrouper.group(files).isEmpty())
    }

    @Test
    fun durationMismatchPreventsVideoGrouping() {
        val base = 0xBEEFL
        val files = listOf(
            file(1, type = MediaType.VIDEO, perceptualHash = base, durationMs = 4_000L, width = 1920, height = 1080),
            file(2, type = MediaType.VIDEO, perceptualHash = base, durationMs = 120_000L, width = 1920, height = 1080)
        )
        // 4s vs 120s -> far-apart duration buckets, no neighbour overlap.
        val groups = SimilarMediaGrouper.group(files)
        assertTrue(groups.none { it.items.size == 2 })
    }

    @Test
    fun exactGroupCollapsesToOneRepresentative() {
        // Three files with identical MD5: only one should enter the similar pool.
        val md5 = "abc123"
        val files = listOf(
            file(1, hash = md5, perceptualHash = 0xAAAA, size = 5000L),
            file(2, hash = md5, perceptualHash = 0xBBBB, size = 4000L),
            file(3, hash = md5, perceptualHash = 0xCCCC, size = 3000L)
        )
        val collapsed = SimilarMediaGrouper.collapseExactGroups(files)
        assertEquals(1, collapsed.size)
        // Representative picks the largest (size 5000 -> id 1).
        assertEquals(1L, collapsed[0].id)
    }

    @Test
    fun favoriteWinsKeepRecommendation() {
        val shared = 0x1111L
        val files = listOf(
            file(1, perceptualHash = shared, size = 9000L, isFavorite = 0),
            file(2, perceptualHash = shared, size = 1000L, isFavorite = 1)
        )
        val groups = SimilarMediaGrouper.group(files)
        assertEquals(1, groups.size)
        assertEquals(2L, groups[0].keepRecommendation.mediaId)
        assertTrue(groups[0].keepRecommendation.reasons.contains(KeepReason.FAVORITE))
    }

    @Test
    fun recomputeRecommendationAfterRepresentativeDeleted() {
        val shared = 0x2222L
        val files = listOf(
            file(1, perceptualHash = shared, size = 9000L, isFavorite = 0),
            file(2, perceptualHash = shared, size = 5000L, isFavorite = 1)
        )
        val groups = SimilarMediaGrouper.group(files)
        val original = groups.first()
        // Simulate deletion of the recommended (favorite) item.
        val remaining = original.copy(items = original.items.filter { it.file.id != 2L })
        val recomputed = SimilarMediaGrouper.recomputeRecommendation(remaining)
        assertEquals(1L, recomputed.keepRecommendation.mediaId)
    }

    @Test
    fun inputOrderDoesNotAffectOutput() {
        val ph = 0x3333L
        val a = file(1, perceptualHash = ph)
        val b = file(2, perceptualHash = ph)
        val c = file(3, perceptualHash = ph)
        val g1 = SimilarMediaGrouper.group(listOf(a, b, c))
        val g2 = SimilarMediaGrouper.group(listOf(c, a, b))
        assertEquals(g1.size, g2.size)
        assertEquals(g1[0].items.map { it.file.id }.toSet(), g2[0].items.map { it.file.id }.toSet())
    }
}
