package dev.yikuo.youcommuter.core.model

import java.time.Instant

@JvmInline
value class StationId(val value: String) {
    init { require(value.isNotBlank()) { "Station ID cannot be blank" } }
}

/** A missing count is unknown, not an empty station. */
data class AvailabilityCounts(
    val totalBikes: Int?,
    val regularBikes: Int?,
    val electricBikes: Int?,
    val returnDocks: Int?,
) {
    init {
        require(listOfNotNull(totalBikes, regularBikes, electricBikes, returnDocks).all { it >= 0 })
    }
}

enum class BikeKind {
    All, Regular, Electric;

    fun count(counts: AvailabilityCounts): Int? = when (this) {
        All -> counts.totalBikes
        Regular -> counts.regularBikes
        Electric -> counts.electricBikes
    }
}

data class StationObservation(
    val stationId: StationId,
    val observationId: String,
    val observedAt: Instant?,
    val providerUpdatedAt: Instant,
    val fetchedAt: Instant,
    val expectedIntervalSeconds: Long,
    val timestampQuality: TimestampQuality,
    val serviceStatus: StationServiceStatus,
    val counts: AvailabilityCounts,
    val freshness: Freshness,
    val stationVersion: String,
)

enum class Freshness { Fresh, Aging, Stale, Unavailable }

enum class TimestampQuality { FeedSnapshot, ProviderOnly }

enum class StationServiceStatus { Operating, TemporarilyClosed, Retired, Unknown }

enum class StationSystemType { YouBike1, YouBike2, Unknown }

/** Stable selection metadata, independent of inventory observations. */
data class StationLocation(
    val id: StationId,
    val name: String,
    val address: String,
    val regionCode: String,
    val latitude: Double,
    val longitude: Double,
)

fun BikeStation.location() = StationLocation(id, name, address, regionCode, latitude, longitude)

data class BikeStation(
    val id: StationId,
    val name: String,
    val address: String,
    val regionCode: String,
    val latitude: Double,
    val longitude: Double,
    val capacity: Int?,
    val systemType: StationSystemType,
    val distanceMeters: Int?,
    val observation: StationObservation?,
)

data class StationPage(
    val stations: List<BikeStation>,
    val nextCursor: String?,
)

sealed interface StationResult<out T> {
    data class Success<T>(val value: T) : StationResult<T>
    data class Failure(val reason: FailureReason) : StationResult<Nothing>
}
