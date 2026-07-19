package com.example.mediadedup.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [PerceptualHash]. These exercise the pure algorithm
 * (grayscale -> DCT -> 64-bit hash -> hamming distance) without any Android
 * dependencies; instrumented tests would be needed to cover Bitmap decoding.
 */
class PerceptualHashTest {

    private val n = PerceptualHash.PERCEPTUAL_HASH_SIZE // 32
    private val size = n * n

    /**
     * A flat image has (theoretically) no AC energy, but floating-point noise in
     * the DCT can produce tiny nonzero coefficients. The spec (§19.2) requires
     * the result be *stable*, not a specific value, so we assert determinism
     * rather than == 0. Both all-black and all-white must also be stable.
     */
    @Test
    fun flatImageProducesStableHash() {
        val gray = IntArray(size) { 128 }
        val a = PerceptualHash.compute(gray)
        val b = PerceptualHash.compute(gray)
        assertEquals(a, b)
    }

    @Test
    fun allBlackAndAllWhiteAreStable() {
        val black = IntArray(size) { 0 }
        val white = IntArray(size) { 255 }
        assertEquals(PerceptualHash.compute(black), PerceptualHash.compute(black))
        assertEquals(PerceptualHash.compute(white), PerceptualHash.compute(white))
        // Note: black and white produce *different* hashes because the DC term
        // (which is included in the hash bits but excluded from the mean) scales
        // with overall luminance. Stability is what matters, not equality.
    }

    /** Deterministic: same input always yields the same hash. */
    @Test
    fun computeIsDeterministic() {
        val matrix = gradientMatrix()
        val a = PerceptualHash.compute(matrix)
        val b = PerceptualHash.compute(matrix)
        assertEquals(a, b)
    }

    /** Different content yields different hashes. */
    @Test
    fun distinctImagesProduceDistinctHashes() {
        val horizontal = horizontalGradientMatrix()
        val vertical = verticalGradientMatrix()
        assertNotEquals(
            PerceptualHash.compute(horizontal),
            PerceptualHash.compute(vertical)
        )
    }

    // ---- Hamming distance ----

    @Test
    fun hammingDistanceIdenticalHashIsZero() {
        val h = PerceptualHash.compute(horizontalGradientMatrix())
        assertEquals(0, PerceptualHash.hammingDistance(h, h))
    }

    @Test
    fun hammingDistanceSingleBitDifferenceIsOne() {
        val h = 0b1010_1010L
        val flipped = h xor (1L shl 3)
        assertEquals(1, PerceptualHash.hammingDistance(h, flipped))
    }

    @Test
    fun hammingDistanceThresholdBoundary() {
        // Exactly 8 differing bits -> still similar (<= threshold).
        val base = 0L
        val eightBits = base or 0xFF // lowest 8 bits set
        assertEquals(8, PerceptualHash.hammingDistance(base, eightBits))
        assertTrue(PerceptualHash.isSimilar(base, eightBits))

        // 9 differing bits -> not similar.
        val nineBits = eightBits or (1L shl 8)
        assertEquals(9, PerceptualHash.hammingDistance(base, nineBits))
        assertTrue(!PerceptualHash.isSimilar(base, nineBits))
    }

    @Test
    fun hammingDistanceHandlesNegativeLongs() {
        // The sign bit (bit 63) must count like any other.
        val a = 0L
        val b = 1L shl 63 // Long.MIN_VALUE
        assertEquals(1, PerceptualHash.hammingDistance(a, b))
    }

    // ---- Input validation ----

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongSizedMatrix() {
        PerceptualHash.compute(IntArray(10))
    }

    // ---- DCT sanity ----

    @Test
    fun dctIsDeterministicAndOrthonormal() {
        val input = gradientMatrix()
        val first = PerceptualHash.dct2d(input, n)
        val second = PerceptualHash.dct2d(input, n)
        assertEquals(first.size, second.size)
        for (i in first.indices) {
            assertEquals(first[i], second[i], 1e-9)
        }
        // For an all-ones (flat) signal only the DC term is nonzero.
        val flat = IntArray(size) { 1 }
        val dctFlat = PerceptualHash.dct2d(flat, n)
        // AC coefficients near zero; DC = N (orthonormal scaling).
        for (u in 0 until n) {
            for (v in 0 until n) {
                val value = dctFlat[u * n + v]
                if (u == 0 && v == 0) {
                    assertEquals(n.toDouble(), value, 1e-6)
                } else {
                    assertTrue("AC[$u,$v]=$value should be ~0", kotlin.math.abs(value) < 1e-6)
                }
            }
        }
    }

    // ---- Fixtures ----

    private fun gradientMatrix(): IntArray {
        val m = IntArray(size)
        for (i in 0 until n) {
            for (j in 0 until n) {
                m[i * n + j] = ((i + j) * 4) and 0xFF
            }
        }
        return m
    }

    private fun horizontalGradientMatrix(): IntArray {
        val m = IntArray(size)
        for (i in 0 until n) {
            for (j in 0 until n) {
                m[i * n + j] = (j * 8) and 0xFF
            }
        }
        return m
    }

    private fun verticalGradientMatrix(): IntArray {
        val m = IntArray(size)
        for (i in 0 until n) {
            for (j in 0 until n) {
                m[i * n + j] = (i * 8) and 0xFF
            }
        }
        return m
    }
}
