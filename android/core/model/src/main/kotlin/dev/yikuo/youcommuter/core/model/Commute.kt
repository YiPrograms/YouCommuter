package dev.yikuo.youcommuter.core.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

data class RouteStation(val id: StationId, val name: String) {
    init { require(name.isNotBlank()) { "Station name cannot be blank" } }
}

data class StationGroup(val primary: RouteStation, val backups: List<RouteStation> = emptyList()) {
    init {
        require(backups.size <= 5)
        require(backups.none { it.id == primary.id } && backups.distinctBy { it.id }.size == backups.size)
    }
}

data class CommuteRoute(
    val id: UUID,
    val name: String,
    val borrow: StationGroup,
    val returns: StationGroup,
    val departure: LocalTime,
    val weekdays: Set<DayOfWeek>,
    val revision: Long,
    val reminderMinutesBefore: Int? = null,
    val lowBikeThreshold: Int = DefaultLowBikeThreshold,
    val lowDockThreshold: Int = DefaultLowDockThreshold,
    val estimatedReturnMinutes: Int? = null,
    val bikeDisplay: BikeKind = BikeKind.All,
    val bikeThreshold: BikeKind = BikeKind.All,
) {
    val thresholdBikeKind: BikeKind get() = if (bikeDisplay == BikeKind.All) bikeThreshold else bikeDisplay

    init {
        require(name.isNotBlank() && name.length <= 40)
        require(revision > 0)
        require(reminderMinutesBefore == null || reminderMinutesBefore in 0..120)
        require(lowBikeThreshold in AvailabilityThresholdRange)
        require(lowDockThreshold in AvailabilityThresholdRange)
        require(estimatedReturnMinutes == null || estimatedReturnMinutes in EstimatedReturnMinutesRange)
    }
}

const val DefaultLowBikeThreshold = 3
const val DefaultLowDockThreshold = 2
val AvailabilityThresholdRange = 0..10
val EstimatedReturnMinutesRange = 1..90

enum class CommutePhase {
    Preparing, WalkingToBorrow, Riding, NearReturn, Completed, Skipped, Cancelled, NeedsReview,
}

val CommutePhase.isTerminal: Boolean
    get() = this in setOf(CommutePhase.Completed, CommutePhase.Skipped, CommutePhase.Cancelled)

data class CommuteSession(
    val id: UUID,
    val routeSnapshot: CommuteRoute,
    val phase: CommutePhase,
    val selectedBorrow: RouteStation,
    val selectedReturn: RouteStation,
    val actualBorrow: RouteStation?,
    val actualReturn: RouteStation?,
    val startedAt: Instant,
    val borrowedAt: Instant?,
    val returnedAt: Instant?,
    val revision: Long,
) {
    init { require(revision > 0) }
}
