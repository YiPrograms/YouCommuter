package dev.yikuo.youcommuter.core.map

import dev.yikuo.youcommuter.core.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class StationMapPointTest {
    @Test fun `location marker has no inventory or unavailable count label`() {
        val location = station().location()
        val point = location.mapPoint()
        assertNull(point.inventory)
        assertEquals(location.id.value, point.stationId)
        assertEquals(location.name, point.name)
        assertTrue(point.hasPosition)
        assertEquals(point.key, station().mapPoint(MapMetric.Bikes).key)
    }

    @Test fun `map uses total bikes or return docks and keeps measured zero`() {
        val station = station()
        assertEquals(12, station.mapPoint(MapMetric.Bikes).count)
        assertEquals(0, station.mapPoint(MapMetric.Docks).count)
        assertTrue(station.mapPoint(MapMetric.Docks, selected = true).selected)
        assertEquals(station.mapPoint(MapMetric.Bikes).key, station.mapPoint(MapMetric.Docks).key)
    }

    @Test fun `stale closed and missing observations never render usable map counts`() {
        val station = station()
        for (freshness in listOf(Freshness.Aging, Freshness.Stale, Freshness.Unavailable)) {
            assertNull(station.copy(observation = station.observation!!.copy(freshness = freshness)).mapPoint(MapMetric.Bikes).count)
        }
        assertNull(station.copy(observation = station.observation!!.copy(serviceStatus = StationServiceStatus.TemporarilyClosed))
            .mapPoint(MapMetric.Bikes).count)
        assertNull(station.copy(observation = null).mapPoint(MapMetric.Docks).count)
    }

    @Test fun `nonfinite and unprojectable coordinates are excluded`() {
        val point = station().mapPoint(MapMetric.Bikes)
        assertTrue(point.hasPosition)
        assertFalse(point.copy(latitude = Double.NaN).hasPosition)
        assertFalse(point.copy(longitude = Double.POSITIVE_INFINITY).hasPosition)
        assertFalse(point.copy(latitude = 90.0).hasPosition)
        assertFalse(point.copy(longitude = 181.0).hasPosition)
    }

    private fun station(): BikeStation {
        val at = Instant.parse("2026-10-20T00:00:00Z")
        val id = StationId("station")
        return BikeStation(id, "Station", "", "00", 25.0, 121.5, 12, StationSystemType.YouBike2, null,
            StationObservation(id, "observation", at, at, at, 60, TimestampQuality.FeedSnapshot,
                StationServiceStatus.Operating, AvailabilityCounts(12, 10, 2, 0), Freshness.Fresh, "version"))
    }
}
