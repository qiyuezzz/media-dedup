package com.example.mediadedup.util

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Perceptual hash (pHash) implementation for near-duplicate media detection.
 *
 * Pipeline: grayscale -> 32x32 -> 2D DCT-II -> top-left 8x8 low-frequency
 * coefficients -> 64-bit hash (each bit = coefficient >= mean of the 8x8 block,
 * excluding the DC term from the mean). The DC bit is still retained in the hash.
 *
 * This module is pure Kotlin with no Android dependencies so it can be unit-tested
 * on the JVM. Android-specific media decoding lives in [com.example.mediadedup.data.media.MediaFingerprintReader].
 *
 * Bump [PERCEPTUAL_HASH_VERSION] whenever the algorithm changes so cached
 * fingerprints get invalidated.
 */
object PerceptualHash {

    /** Algorithm version. Increment when the pipeline changes to invalidate cache. */
    const val PERCEPTUAL_HASH_VERSION: Int = 1

    /** Side length of the resized grayscale image fed into the DCT. */
    const val PERCEPTUAL_HASH_SIZE: Int = 32

    /** Side length of the low-frequency block used to build the 64-bit hash. */
    const val PERCEPTUAL_HASH_LOW_FREQUENCY_SIZE: Int = 8

    /** Hamming distance threshold at/under which two hashes are considered similar. */
    const val SIMILARITY_THRESHOLD: Int = 8

    /**
     * Compute a 64-bit perceptual hash from a 32x32 grayscale matrix.
     *
     * Values are luminance in [0, 255]; row-major, length must equal
     * [PERCEPTUAL_HASH_SIZE] * [PERCEPTUAL_HASH_SIZE].
     */
    fun compute(grayscale: IntArray): Long {
        require(grayscale.size == PERCEPTUAL_HASH_SIZE * PERCEPTUAL_HASH_SIZE) {
            "grayscale matrix must be ${PERCEPTUAL_HASH_SIZE}x${PERCEPTUAL_HASH_SIZE}"
        }
        val dct = dct2d(grayscale, PERCEPTUAL_HASH_SIZE)
        // Mean of the 8x8 low-frequency block, excluding the DC term (index 0,0).
        var sum = 0.0
        for (row in 0 until PERCEPTUAL_HASH_LOW_FREQUENCY_SIZE) {
            for (col in 0 until PERCEPTUAL_HASH_LOW_FREQUENCY_SIZE) {
                if (row == 0 && col == 0) continue
                sum += dct[row * PERCEPTUAL_HASH_SIZE + col]
            }
        }
        val mean = sum / (PERCEPTUAL_HASH_LOW_FREQUENCY_SIZE * PERCEPTUAL_HASH_LOW_FREQUENCY_SIZE - 1)

        var hash = 0L
        var bit = 0
        for (row in 0 until PERCEPTUAL_HASH_LOW_FREQUENCY_SIZE) {
            for (col in 0 until PERCEPTUAL_HASH_LOW_FREQUENCY_SIZE) {
                if (dct[row * PERCEPTUAL_HASH_SIZE + col] > mean) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        return hash
    }

    /**
     * Hamming distance between two 64-bit hashes (number of differing bits).
     * Identical hashes yield 0.
     */
    fun hammingDistance(first: Long, second: Long): Int {
        return java.lang.Long.bitCount(first xor second)
    }

    /** True iff [hammingDistance] is within [SIMILARITY_THRESHOLD]. */
    fun isSimilar(first: Long, second: Long): Boolean {
        return hammingDistance(first, second) <= SIMILARITY_THRESHOLD
    }

    /**
     * 2D DCT-II on a square [size]x[size] matrix. Returns the coefficients in the
     * same row-major layout. Uses the standard separable formulation:
     *   X[u,v] = sum_{x,y} x[x,y] * cos((2x+1)u pi / 2N) * cos((2y+1)v pi / 2N) * C(u)*C(v)
     * with C(0)=sqrt(1/N), C(k>0)=sqrt(2/N). The C(u)*C(v) scaling is applied once.
     */
    internal fun dct2d(input: IntArray, size: Int): DoubleArray {
        val n = size.toDouble()
        // Precompute cosine basis: cosines[u][x] = cos((2x+1)*u*pi / 2N)
        val cosines = Array(size) { DoubleArray(size) }
        for (u in 0 until size) {
            for (x in 0 until size) {
                cosines[u][x] = cos((2 * x + 1) * u * Math.PI / (2.0 * n))
            }
        }
        // Separable 1D DCT along rows then columns.
        // temp[row][v] = sum_x input[row][x] * cosines[v][x]
        val temp = DoubleArray(size * size)
        for (row in 0 until size) {
            for (v in 0 until size) {
                var s = 0.0
                val base = row * size
                val cosV = cosines[v]
                for (x in 0 until size) {
                    s += input[base + x] * cosV[x]
                }
                temp[row * size + v] = s
            }
        }
        // out[u][v] = sum_row temp[row][v] * cosines[u][row]
        val out = DoubleArray(size * size)
        for (u in 0 until size) {
            val cosU = cosines[u]
            for (v in 0 until size) {
                var s = 0.0
                for (row in 0 until size) {
                    s += temp[row * size + v] * cosU[row]
                }
                out[u * size + v] = s
            }
        }
        // Apply the C(u)*C(v) orthonormal scaling.
        val c0 = sqrt(1.0 / n)
        val ck = sqrt(2.0 / n)
        for (u in 0 until size) {
            val cu = if (u == 0) c0 else ck
            for (v in 0 until size) {
                val cv = if (v == 0) c0 else ck
                out[u * size + v] *= cu * cv
            }
        }
        return out
    }
}
