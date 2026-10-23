package dev.yikuo.youcommuter.core.domain

import dev.yikuo.youcommuter.core.model.*

val BikeStation.usableCounts: AvailabilityCounts?
    get() = observation?.takeIf {
        it.freshness == Freshness.Fresh && it.serviceStatus == StationServiceStatus.Operating
    }?.counts

enum class AvailabilityLevel { Unknown, Empty, Low, Available }

fun availabilityLevel(count: Int?, threshold: Int): AvailabilityLevel = when {
    count == null -> AvailabilityLevel.Unknown
    count == 0 -> AvailabilityLevel.Empty
    count <= threshold -> AvailabilityLevel.Low
    else -> AvailabilityLevel.Available
}

/** Preserve route order, preferring sufficient inventory over a merely positive count. */
fun recommendedStation(
    stations: List<RouteStation>,
    counts: Map<StationId, Int?>,
    threshold: Int,
): StationId? = stations.firstOrNull { availabilityLevel(counts[it.id], threshold) == AvailabilityLevel.Available }?.id
    ?: stations.firstOrNull { availabilityLevel(counts[it.id], threshold) == AvailabilityLevel.Low }?.id
