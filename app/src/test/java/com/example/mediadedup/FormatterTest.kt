package com.example.mediadedup

import com.example.mediadedup.util.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatterTest {

    @Test
    fun zeroOrNegativeDurationReturnsEmptyString() {
        assertEquals("", formatDuration(0L))
        assertEquals("", formatDuration(-1L))
    }

    @Test
    fun subMinuteDurationFormattedAsMinutesSeconds() {
        assertEquals("0:07", formatDuration(7_000L))
        assertEquals("0:59", formatDuration(59_999L))
    }

    @Test
    fun subHourDurationPadsSecondsToTwoDigits() {
        assertEquals("1:05", formatDuration(65_000L))
        assertEquals("12:34", formatDuration((12 * 60 + 34) * 1_000L))
    }

    @Test
    fun hourOrLongerDurationFormattedAsHoursMinutesSeconds() {
        assertEquals("1:00:00", formatDuration(3_600_000L))
        assertEquals("1:02:03", formatDuration((3600 + 2 * 60 + 3) * 1_000L))
    }

    @Test
    fun fractionalMillisecondsAreTruncatedNotRounded() {
        // 1:00.999 -> 1:00 (truncates, does not round up to 1:01)
        assertEquals("1:00", formatDuration(60_999L))
    }
}
