package com.lokey0905.FitnessHooks

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal object HealthConnectStepPlan {
    const val MAX_COUNT = 1_000_000

    data class Interval(
        val start: Instant,
        val end: Instant,
        val startOffset: ZoneOffset,
        val endOffset: ZoneOffset,
        val clientRecordId: String,
    )

    fun currentHourStartSeconds(now: Instant, zone: ZoneId): Long =
        now.atZone(zone).truncatedTo(ChronoUnit.HOURS).toEpochSecond()

    fun interval(now: Instant, zone: ZoneId, expectedHourStartSeconds: Long): Interval {
        require(currentHourStartSeconds(now, zone) == expectedHourStartSeconds)
        val start = Instant.ofEpochSecond(expectedHourStartSeconds)
        require(now.isAfter(start))
        return Interval(
            start = start,
            end = now,
            startOffset = zone.rules.getOffset(start),
            endOffset = zone.rules.getOffset(now),
            clientRecordId = "fitness_hooks_manual_hour_$expectedHourStartSeconds",
        )
    }

    fun nextRevision(previous: Long, nowMillis: Long): Long {
        require(previous >= 0 && previous < Long.MAX_VALUE)
        return maxOf(previous + 1, nowMillis, 1)
    }
}
