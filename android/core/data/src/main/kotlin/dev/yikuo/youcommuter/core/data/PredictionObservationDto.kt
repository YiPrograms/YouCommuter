package dev.yikuo.youcommuter.core.data

import dev.yikuo.youcommuter.core.model.AvailabilityCounts
import dev.yikuo.youcommuter.core.model.Freshness
import dev.yikuo.youcommuter.core.model.StationId
import dev.yikuo.youcommuter.core.model.StationObservation
import dev.yikuo.youcommuter.core.model.StationServiceStatus
import dev.yikuo.youcommuter.core.model.TimestampQuality
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ObservationDto(
    val stationId: String,
    val observationId: String,
    val observedAt: String?,
    val providerUpdatedAt: String,
    val fetchedAt: String,
    val timestampQuality: String,
    val expectedIntervalSeconds: Long,
    val status: String,
    val metrics: AvailabilityMetricsDto,
    val quality: String,
    val stationVersion: String,
) {
    fun toDomain(stationId: StationId): StationObservation {
        require(this.stationId == stationId.value && observationId.isNotBlank() && stationVersion.isNotBlank())
        return StationObservation(
            stationId = stationId,
            observationId = observationId,
            observedAt = observedAt?.let(Instant::parse),
            providerUpdatedAt = Instant.parse(providerUpdatedAt),
            fetchedAt = Instant.parse(fetchedAt),
            expectedIntervalSeconds = expectedIntervalSeconds.also { require(it > 0) },
            timestampQuality = when (timestampQuality) {
                "feed_snapshot" -> TimestampQuality.FeedSnapshot
                "provider_only" -> TimestampQuality.ProviderOnly
                else -> throw IllegalArgumentException("Unknown timestamp quality")
            },
            serviceStatus = when (status) {
                "operating" -> StationServiceStatus.Operating
                "temporarily_closed" -> StationServiceStatus.TemporarilyClosed
                "retired" -> StationServiceStatus.Retired
                "unknown" -> StationServiceStatus.Unknown
                else -> throw IllegalArgumentException("Unknown station status")
            },
            counts = metrics.toDomain(),
            freshness = when (quality) {
                "fresh" -> Freshness.Fresh
                "aging" -> Freshness.Aging
                "stale" -> Freshness.Stale
                "unavailable" -> Freshness.Unavailable
                else -> throw IllegalArgumentException("Unknown observation quality")
            },
            stationVersion = stationVersion,
        )
    }
}

@Serializable
internal data class AvailabilityMetricsDto(
    @SerialName("rent_total") val rentTotal: Int?,
    @SerialName("rent_regular") val rentRegular: Int?,
    @SerialName("rent_electric") val rentElectric: Int?,
    @SerialName("return_docks") val returnDocks: Int?,
) {
    fun toDomain() = AvailabilityCounts(rentTotal, rentRegular, rentElectric, returnDocks)
}
