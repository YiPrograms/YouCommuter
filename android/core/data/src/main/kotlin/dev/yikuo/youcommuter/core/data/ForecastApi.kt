package dev.yikuo.youcommuter.core.data

import dev.yikuo.youcommuter.core.model.ArrivalDistribution
import dev.yikuo.youcommuter.core.model.ArrivalPoint
import dev.yikuo.youcommuter.core.model.ArrivalSource
import dev.yikuo.youcommuter.core.model.AvailabilityForecast
import dev.yikuo.youcommuter.core.model.AvailabilityMetric
import dev.yikuo.youcommuter.core.model.ForecastRequest
import dev.yikuo.youcommuter.core.model.ForecastRisk
import dev.yikuo.youcommuter.core.model.ForecastStatus
import dev.yikuo.youcommuter.core.model.ForecastSupport
import dev.yikuo.youcommuter.core.model.StationId
import java.time.Instant
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

internal interface ForecastApi {
    @POST
    suspend fun forecasts(
        @Url endpoint: String,
        @Header("Authorization") authorization: String?,
        @Body request: ForecastBatchRequestDto,
    ): ForecastBatchResponseDto
}

@Serializable
internal data class ForecastBatchRequestDto(val items: List<ForecastRequestDto>)

@Serializable
internal data class ForecastRequestDto(
    val stationId: String,
    val metric: String,
    val arrival: ArrivalDistributionDto,
    val lowCountThreshold: Int,
)

internal fun ForecastRequest.toDto() = ForecastRequestDto(
    stationId.value, metric.wireValue(), arrival.toDto(), lowCountThreshold,
)

internal fun AvailabilityMetric.wireValue(): String = when (this) {
    AvailabilityMetric.TotalBikes -> "rent_total"
    AvailabilityMetric.RegularBikes -> "rent_regular"
    AvailabilityMetric.ElectricBikes -> "rent_electric"
    AvailabilityMetric.ReturnDocks -> "return_docks"
}

@Serializable
internal data class ArrivalPointDto(val at: String, val probability: Double)

@Serializable
internal data class ArrivalDistributionDto(
    val points: List<ArrivalPointDto>,
    val source: String,
    val distinctConfirmedRides: Int,
) {
    fun toDomain() = ArrivalDistribution(
        points.map { ArrivalPoint(Instant.parse(it.at), it.probability) },
        when (source) {
            "personal" -> ArrivalSource.Personal
            "routing_prior" -> ArrivalSource.RoutingPrior
            "user_estimate" -> ArrivalSource.UserEstimate
            "mixed" -> ArrivalSource.Mixed
            else -> throw IllegalArgumentException("Unknown arrival source")
        },
        distinctConfirmedRides,
    )
}

private fun ArrivalDistribution.toDto() = ArrivalDistributionDto(
    points.map { ArrivalPointDto(it.at.toString(), it.probability) },
    when (source) {
        ArrivalSource.Personal -> "personal"
        ArrivalSource.RoutingPrior -> "routing_prior"
        ArrivalSource.UserEstimate -> "user_estimate"
        ArrivalSource.Mixed -> "mixed"
    },
    distinctConfirmedRides,
)

@Serializable
internal data class ForecastBatchResponseDto(val forecasts: List<ForecastDto>)

@Serializable
internal data class ForecastSupportDto(val distinctDays: Int, val effectiveDays: Double, val sameWeekdayDays: Int)

@Serializable
internal data class ForecastDto(
    val stationId: String,
    val metric: String,
    val observation: ObservationDto?,
    val status: String,
    val reasonCodes: List<String>,
    val arrival: ArrivalDistributionDto,
    val medianCount: Int?,
    val p10Count: Int?,
    val p90Count: Int?,
    val probabilityEmpty: Double?,
    val probabilityAtOrBelowThreshold: Double?,
    val lowCountThreshold: Int,
    val riskBand: String,
    val support: ForecastSupportDto,
    val modelVersion: String,
    val calibrationVersion: String?,
    val generatedAt: String,
    val validUntil: String,
) {
    fun toDomain(): AvailabilityForecast {
        val id = StationId(stationId)
        return AvailabilityForecast(
            stationId = id,
            metric = AvailabilityMetric.entries.firstOrNull { it.wireValue() == metric }
                ?: throw IllegalArgumentException("Unknown forecast metric"),
            observation = observation?.toDomain(id),
            status = when (status) {
                "building" -> ForecastStatus.Building
                "experimental" -> ForecastStatus.Experimental
                "validated" -> ForecastStatus.Validated
                "unavailable" -> ForecastStatus.Unavailable
                else -> throw IllegalArgumentException("Unknown forecast status")
            },
            reasonCodes = reasonCodes,
            arrival = arrival.toDomain(),
            medianCount = medianCount,
            p10Count = p10Count,
            p90Count = p90Count,
            probabilityEmpty = probabilityEmpty,
            probabilityAtOrBelowThreshold = probabilityAtOrBelowThreshold,
            lowCountThreshold = lowCountThreshold,
            risk = when (riskBand) {
                "healthy" -> ForecastRisk.Healthy
                "caution" -> ForecastRisk.Caution
                "high" -> ForecastRisk.High
                "unknown" -> ForecastRisk.Unknown
                else -> throw IllegalArgumentException("Unknown forecast risk")
            },
            support = ForecastSupport(support.distinctDays, support.effectiveDays, support.sameWeekdayDays),
            modelVersion = modelVersion,
            calibrationVersion = calibrationVersion,
            generatedAt = Instant.parse(generatedAt),
            validUntil = Instant.parse(validUntil),
        )
    }
}
