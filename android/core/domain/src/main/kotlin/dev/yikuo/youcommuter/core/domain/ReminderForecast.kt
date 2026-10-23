package dev.yikuo.youcommuter.core.domain

import dev.yikuo.youcommuter.core.model.AvailabilityForecast
import dev.yikuo.youcommuter.core.model.AvailabilityMetric
import dev.yikuo.youcommuter.core.model.BikeStation
import dev.yikuo.youcommuter.core.model.ForecastStatus
import dev.yikuo.youcommuter.core.model.Freshness
import dev.yikuo.youcommuter.core.model.StationServiceStatus
import dev.yikuo.youcommuter.core.model.EstimatedReturnMinutesRange
import dev.yikuo.youcommuter.core.model.StationObservation
import java.time.Duration
import java.time.Instant

/** A notification must not outlive either its forecast or its departure occurrence. */
fun departureReminderExpiry(departure: Instant, vararg forecasts: AvailabilityForecast?): Instant =
    minOf(departure.plusSeconds(5 * 60), forecasts.filterNotNull().minOfOrNull { it.validUntil } ?: Instant.MAX)

fun estimatedReturnAt(departure: Instant, minutes: Int?): Instant? =
    minutes?.takeIf { it in EstimatedReturnMinutesRange }?.let { departure.plusSeconds(it * 60L) }

/** Discard estimates for a different occurrence, epoch, or expired observation. */
fun usableReminderForecast(
    forecast: AvailabilityForecast?,
    station: BikeStation?,
    arrival: Instant,
    now: Instant,
    metric: AvailabilityMetric = AvailabilityMetric.TotalBikes,
): AvailabilityForecast? {
    if (forecast == null || station == null || arrival <= now || forecast.stationId != station.id ||
        forecast.metric != metric || forecast.validUntil <= now ||
        forecast.generatedAt > now.plusSeconds(60) ||
        forecast.arrival.points.size != 1 || forecast.arrival.points.single().at != arrival
    ) return null
    val observation = forecast.observation ?: return null
    if (observation.stationVersion != station.observation?.stationVersion ||
        observation.serviceStatus != StationServiceStatus.Operating
    ) return null
    val freshness = AvailabilityFreshness.classify(
        observation.observedAt, now, Duration.ofSeconds(observation.expectedIntervalSeconds),
    )
    if (freshness == Freshness.Stale || freshness == Freshness.Unavailable) return null
    if (forecast.status == ForecastStatus.Experimental &&
        (forecast.support.distinctDays < 7 || forecast.support.effectiveDays < 5)
    ) return null
    return forecast
}

/** Current-only notifications also expire before their unflagged inventory becomes stale. */
fun inventoryReminderExpiry(baseExpiry: Instant, observations: List<StationObservation>, now: Instant): Instant =
    minOf(baseExpiry, observations.mapNotNull { observation ->
        val at = observation.observedAt ?: return@mapNotNull null
        val cadence = Duration.ofSeconds(observation.expectedIntervalSeconds)
        when (AvailabilityFreshness.classify(at, now, cadence)) {
            Freshness.Fresh, Freshness.Aging -> at.plus(maxOf(Duration.ofMinutes(10), cadence.multipliedBy(3)))
            // Already unavailable/stale values are explicitly warned in the notification.
            Freshness.Stale, Freshness.Unavailable -> null
        }
    }.minOrNull() ?: Instant.MAX)
