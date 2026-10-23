package dev.yikuo.youcommuter.core.domain

import dev.yikuo.youcommuter.core.model.*
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderForecastTest {
    private val now = Instant.parse("2026-10-03T00:01:00Z")
    private val departure = now.plusSeconds(14 * 60)
    private val stationId = StationId("youbike:500101001")
    private val observation = StationObservation(
        stationId, "obs-current", now.minusSeconds(60), now, now, 60,
        TimestampQuality.FeedSnapshot, StationServiceStatus.Operating,
        AvailabilityCounts(12, null, null, 8), Freshness.Fresh, "1",
    )
    private val station = BikeStation(
        stationId, "Station", "", "00", 25.0, 121.5, 20, StationSystemType.YouBike2, null, observation,
    )
    private val forecast = AvailabilityForecast(
        stationId, AvailabilityMetric.TotalBikes, observation, ForecastStatus.Experimental, emptyList(),
        ArrivalDistribution(listOf(ArrivalPoint(departure, 1.0)), ArrivalSource.UserEstimate),
        9, 7, 12, null, null, 3, ForecastRisk.Unknown, ForecastSupport(7, 6.0, 7),
        "analog-delta-v1", null, now, now.plusSeconds(540),
    )

    @Test fun `supported departure estimate is usable`() {
        assertEquals(forecast, usableReminderForecast(forecast, station, departure, now))
    }

    @Test fun `old occurrence expired observation and changed metadata cannot show estimates`() {
        assertNull(usableReminderForecast(forecast, station, departure.plusSeconds(60), now))
        assertNull(usableReminderForecast(forecast.copy(validUntil = now), station, departure, now))
        assertNull(usableReminderForecast(forecast, station.copy(observation = observation.copy(stationVersion = "2")), departure, now))
        assertNull(usableReminderForecast(forecast, station, departure, now.plusSeconds(600)))
        assertNull(usableReminderForecast(forecast.copy(support = ForecastSupport(6, 5.5, 6)), station, departure, now))
    }

    @Test fun `cold start remains a building state`() {
        val building = forecast.copy(status = ForecastStatus.Building, medianCount = null, p10Count = null, p90Count = null)
        assertEquals(ForecastStatus.Building, usableReminderForecast(building, station, departure, now)?.status)
    }

    @Test fun `notification expires with forecast and cannot outlive departure`() {
        assertEquals(forecast.validUntil, departureReminderExpiry(departure, forecast))
        assertEquals(departure.plusSeconds(300), departureReminderExpiry(departure, null))
        assertEquals(
            departure.plusSeconds(300),
            departureReminderExpiry(departure, forecast.copy(validUntil = departure.plusSeconds(3600))),
        )
    }

    @Test fun `current-only notifications expire when inventory becomes stale`() {
        val base = departure.plusSeconds(300)
        assertEquals(observation.observedAt!!.plusSeconds(600), inventoryReminderExpiry(base, listOf(observation), now))
        assertEquals(base, inventoryReminderExpiry(base, emptyList(), now))
        assertEquals(base, inventoryReminderExpiry(base, listOf(observation.copy(observedAt = now.minusSeconds(601))), now))
    }

    @Test fun `return estimate crosses midnight and optional values stay explicit`() {
        val lateDeparture = Instant.parse("2026-10-03T15:55:00Z") // 23:55 Taipei.
        assertEquals(Instant.parse("2026-10-03T16:20:00Z"), estimatedReturnAt(lateDeparture, 25))
        assertNull(estimatedReturnAt(departure, null))
        assertNull(estimatedReturnAt(departure, 0))
        assertNull(estimatedReturnAt(departure, 91))
    }

    @Test fun `return forecast must match its own arrival and metric`() {
        val arrival = requireNotNull(estimatedReturnAt(departure, 25))
        val returns = forecast.copy(
            metric = AvailabilityMetric.ReturnDocks,
            arrival = ArrivalDistribution(listOf(ArrivalPoint(arrival, 1.0)), ArrivalSource.UserEstimate),
        )
        assertEquals(returns, usableReminderForecast(returns, station, arrival, now, AvailabilityMetric.ReturnDocks))
        assertNull(usableReminderForecast(returns, station, departure, now, AvailabilityMetric.ReturnDocks))
        assertNull(usableReminderForecast(returns, station, arrival, now))
        val earlier = returns.copy(validUntil = now.plusSeconds(300))
        assertEquals(earlier.validUntil, departureReminderExpiry(departure, forecast, earlier))
    }
}
