package com.example.mediadedup.util

fun formatSize(size: Long): String {
    val kb = size / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "%.2f GB".format(gb)
        mb >= 1 -> "%.2f MB".format(mb)
        else -> "%.2f KB".format(kb)
    }
}

/**
 * Formats a duration in milliseconds as `m:ss` (< 1h) or `H:mm:ss` (>= 1h).
 * Returns an empty string for non-positive input so callers can `.filter { it.isNotEmpty() }`
 * to skip the field entirely when the duration is unknown.
 */
fun formatDuration(ms: Long): String {
    if (ms <= 0L) return ""
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
