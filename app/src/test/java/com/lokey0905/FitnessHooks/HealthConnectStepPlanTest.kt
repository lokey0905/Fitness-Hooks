package com.lokey0905.FitnessHooks

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectStepPlanTest {
    @Test
    fun hourUsesLocalBoundaryInHalfHourAndQuarterHourZones() {
        val now = Instant.parse("2026-10-11T10:22:31Z")
        assertEquals(
            Instant.parse("2026-10-11T09:30:00Z").epochSecond,
            HealthConnectStepPlan.currentHourStartSeconds(now, ZoneId.of("Asia/Kolkata")),
        )
        assertEquals(
            Instant.parse("2026-10-11T10:15:00Z").epochSecond,
            HealthConnectStepPlan.currentHourStartSeconds(now, ZoneId.of("Asia/Kathmandu")),
        )
    }

    @Test
    fun repeatedSyncUpdatesOneClientRecord() {
        val firstTime = Instant.parse("2026-10-11T10:05:00Z")
        val nextTime = Instant.parse("2026-10-11T10:25:00Z")
        val hour = HealthConnectStepPlan.currentHourStartSeconds(firstTime, ZoneOffset.UTC)
        val first = HealthConnectStepPlan.interval(firstTime, ZoneOffset.UTC, hour)
        val next = HealthConnectStepPlan.interval(nextTime, ZoneOffset.UTC, hour)
        assertEquals(first.clientRecordId, next.clientRecordId)
        assertEquals(first.start, next.start)
        assertEquals(nextTime, next.end)
        assertTrue(next.end.isAfter(first.end))
    }

    @Test(expected = IllegalArgumentException::class)
    fun staleHourCannotReceivePendingSave() {
        val oldHour = Instant.parse("2026-10-11T10:00:00Z").epochSecond
        HealthConnectStepPlan.interval(Instant.parse("2026-10-11T11:00:00Z"), ZoneOffset.UTC, oldHour)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroLengthIntervalDoesNotInventFutureEnd() {
        val boundary = Instant.parse("2026-10-11T10:00:00Z")
        HealthConnectStepPlan.interval(boundary, ZoneOffset.UTC, boundary.epochSecond)
    }

    @Test
    fun repeatedDstHourGetsDistinctIdsAndOffsets() {
        val zone = ZoneId.of("America/New_York")
        val first = Instant.parse("2026-11-01T05:30:00Z")
        val second = Instant.parse("2026-11-01T06:30:00Z")
        val firstPlan = HealthConnectStepPlan.interval(
            first, zone, HealthConnectStepPlan.currentHourStartSeconds(first, zone),
        )
        val secondPlan = HealthConnectStepPlan.interval(
            second, zone, HealthConnectStepPlan.currentHourStartSeconds(second, zone),
        )
        assertNotEquals(firstPlan.clientRecordId, secondPlan.clientRecordId)
        assertEquals(ZoneOffset.ofHours(-4), firstPlan.startOffset)
        assertEquals(ZoneOffset.ofHours(-5), secondPlan.startOffset)
    }

    @Test
    fun springDstGapStillUsesCurrentRealHour() {
        val now = Instant.parse("2026-03-08T07:15:00Z")
        val zone = ZoneId.of("America/New_York")
        val hour = HealthConnectStepPlan.currentHourStartSeconds(now, zone)
        val plan = HealthConnectStepPlan.interval(now, zone, hour)
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), plan.start)
        assertEquals(now, plan.end)
        assertEquals(ZoneOffset.ofHours(-4), plan.startOffset)
    }

    @Test
    fun persistedRevisionWinsOverEqualOrReversedClock() {
        assertEquals(101, HealthConnectStepPlan.nextRevision(100, 100))
        assertEquals(101, HealthConnectStepPlan.nextRevision(100, 10))
        assertEquals(200, HealthConnectStepPlan.nextRevision(100, 200))
        assertEquals(1, HealthConnectStepPlan.nextRevision(0, -1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun revisionNeverOverflowsIntoOlderVersion() {
        HealthConnectStepPlan.nextRevision(Long.MAX_VALUE, 100)
    }
}
