package dev.yikuo.youcommuter.core.domain

import dev.yikuo.youcommuter.core.model.*
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class StationPriorityTest {
    private val refs = (1..4).map { RouteStation(StationId("$it"), "Station $it") }

    @Test fun `healthy stations take priority in route order rather than by largest inventory`() {
        val counts = mapOf(refs[0].id to 2, refs[1].id to 4, refs[2].id to 30, refs[3].id to 0)
        assertEquals(refs[1].id, recommendedStation(refs, counts, 3))
        assertEquals(refs[0].id, recommendedStation(refs, counts, 1))
    }

    @Test fun `low positive counts are a fallback while zero and unknown are never recommended`() {
        assertEquals(refs[2].id, recommendedStation(refs,
            mapOf(refs[0].id to 0, refs[1].id to null, refs[2].id to 2, refs[3].id to 3), 3))
        assertNull(recommendedStation(refs, mapOf(refs[0].id to 0), 3))
        assertEquals(AvailabilityLevel.Empty, availabilityLevel(0, 0))
        assertEquals(AvailabilityLevel.Available, availabilityLevel(1, 0))
        assertEquals(AvailabilityLevel.Low, availabilityLevel(3, 3))
        assertEquals(AvailabilityLevel.Unknown, availabilityLevel(null, 3))
    }

    @Test fun `single displayed bike kind determines threshold even if stored threshold differs`() {
        val route = CommuteRoute(UUID.randomUUID(), "Work", StationGroup(refs[0]), StationGroup(refs[1]),
            LocalTime.NOON, emptySet(), 1, bikeDisplay = BikeKind.Electric, bikeThreshold = BikeKind.Regular)
        val counts = AvailabilityCounts(12, 12, 0, 10)
        assertEquals(BikeKind.Electric, route.thresholdBikeKind)
        assertEquals(0, route.thresholdBikeKind.count(counts))
        assertEquals(AvailabilityMetric.ElectricBikes, route.thresholdBikeKind.forecastMetric)
        assertEquals(12, route.copy(bikeDisplay = BikeKind.All).thresholdBikeKind.count(counts))
        assertNull(BikeKind.Electric.count(counts.copy(electricBikes = null)))
    }

    @Test fun `stale closed and missing inventory cannot produce a healthy recommendation`() {
        val at = Instant.parse("2026-10-21T00:00:00Z")
        val id = refs[0].id
        val station = BikeStation(id, "Station", "", "00", 25.0, 121.0, 30, StationSystemType.YouBike2, null,
            StationObservation(id, "obs", at, at, at, 60, TimestampQuality.FeedSnapshot,
                StationServiceStatus.Operating, AvailabilityCounts(10, 8, 2, 20), Freshness.Fresh, "v"))
        assertEquals(10, station.usableCounts!!.totalBikes)
        assertNull(station.copy(observation = station.observation!!.copy(freshness = Freshness.Aging)).usableCounts)
        assertNull(station.copy(observation = station.observation!!.copy(serviceStatus = StationServiceStatus.TemporarilyClosed)).usableCounts)
        assertNull(station.copy(observation = null).usableCounts)
    }
}
