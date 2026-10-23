package dev.yikuo.youcommuter.core.model

import java.time.Instant

enum class AvailabilityMetric { TotalBikes, RegularBikes, ElectricBikes, ReturnDocks }
enum class ForecastStatus { Building, Experimental, Validated, Unavailable }
enum class ForecastRisk { Healthy, Caution, High, Unknown }
enum class ArrivalSource { Personal, RoutingPrior, UserEstimate, Mixed }

data class ArrivalPoint(val at: Instant, val probability: Double)

data class ArrivalDistribution(
    val points: List<ArrivalPoint>,
    val source: ArrivalSource,
    val distinctConfirmedRides: Int = 0,
) {
    init {
        require(points.size in 1..91)
        require(points.all { it.probability.isFinite() && it.probability >= 0 })
        require(kotlin.math.abs(points.sumOf { it.probability } - 1.0) <= 1e-6)
        require(points.zipWithNext().all { (first, second) -> first.at < second.at })
        require(distinctConfirmedRides >= 0)
    }
}

data class ForecastRequest(
    val stationId: StationId,
    val metric: AvailabilityMetric,
    val arrival: ArrivalDistribution,
    val lowCountThreshold: Int,
) {
    init { require(lowCountThreshold in AvailabilityThresholdRange) }
}

data class ForecastSupport(val distinctDays: Int, val effectiveDays: Double, val sameWeekdayDays: Int) {
    init {
        require(distinctDays >= 0 && sameWeekdayDays in 0..distinctDays)
        require(effectiveDays.isFinite() && effectiveDays >= 0 && effectiveDays <= distinctDays + 1e-6)
    }
}

/** Experimental estimates deliberately carry no numeric shortage probabilities. */
data class AvailabilityForecast(
    val stationId: StationId,
    val metric: AvailabilityMetric,
    val observation: StationObservation?,
    val status: ForecastStatus,
    val reasonCodes: List<String>,
    val arrival: ArrivalDistribution,
    val medianCount: Int?,
    val p10Count: Int?,
    val p90Count: Int?,
    val probabilityEmpty: Double?,
    val probabilityAtOrBelowThreshold: Double?,
    val lowCountThreshold: Int,
    val risk: ForecastRisk,
    val support: ForecastSupport,
    val modelVersion: String,
    val calibrationVersion: String?,
    val generatedAt: Instant,
    val validUntil: Instant,
) {
    init {
        require(lowCountThreshold in AvailabilityThresholdRange && modelVersion.isNotBlank())
        require(observation == null || observation.stationId == stationId)
        val hasEstimate = status == ForecastStatus.Experimental || status == ForecastStatus.Validated
        if (hasEstimate) {
            require(p10Count != null && medianCount != null && p90Count != null)
            require(p10Count >= 0 && p10Count <= medianCount && medianCount <= p90Count)
        } else {
            require(listOfNotNull(p10Count, medianCount, p90Count).isEmpty())
        }
        listOfNotNull(probabilityEmpty, probabilityAtOrBelowThreshold).forEach {
            require(it.isFinite() && it in 0.0..1.0)
        }
        if (status != ForecastStatus.Validated) {
            require(probabilityEmpty == null && probabilityAtOrBelowThreshold == null && risk == ForecastRisk.Unknown)
        } else {
            require(!calibrationVersion.isNullOrBlank())
        }
    }
}

val BikeKind.forecastMetric: AvailabilityMetric get() = when (this) {
    BikeKind.All -> AvailabilityMetric.TotalBikes
    BikeKind.Regular -> AvailabilityMetric.RegularBikes
    BikeKind.Electric -> AvailabilityMetric.ElectricBikes
}
