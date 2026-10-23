package dev.yikuo.youcommuter.core.database

import dev.yikuo.youcommuter.core.model.CommutePhase
import dev.yikuo.youcommuter.core.model.BikeKind
import dev.yikuo.youcommuter.core.model.CommuteRoute
import dev.yikuo.youcommuter.core.model.CommuteSession
import dev.yikuo.youcommuter.core.model.RouteStation
import dev.yikuo.youcommuter.core.model.StationGroup
import dev.yikuo.youcommuter.core.model.StationId
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class PersistenceMapping(private val json: Json = Json) {
    fun routeToEntity(route: CommuteRoute) = RouteEntity(
        id = route.id.toString(),
        name = route.name,
        payload = json.encodeToString(PersistedRoute.from(route)),
    )

    fun routeFromEntity(entity: RouteEntity): CommuteRoute =
        json.decodeFromString<PersistedRoute>(entity.payload).toDomain()

    fun sessionToEntity(session: CommuteSession, lastCommandId: UUID?) = SessionEntity(
        id = session.id.toString(),
        payload = json.encodeToString(PersistedSession.from(session)),
        lastCommandId = lastCommandId?.toString(),
    )

    fun sessionFromEntity(entity: SessionEntity): CommuteSession =
        json.decodeFromString<PersistedSession>(entity.payload).toDomain()
}

@Serializable
private data class PersistedStation(val id: String, val name: String) {
    fun toDomain() = RouteStation(StationId(id), name)

    companion object {
        fun from(station: RouteStation) = PersistedStation(station.id.value, station.name)
    }
}

@Serializable
private data class PersistedGroup(
    val primary: PersistedStation,
    val backups: List<PersistedStation>,
) {
    fun toDomain() = StationGroup(primary.toDomain(), backups.map(PersistedStation::toDomain))

    companion object {
        fun from(group: StationGroup) = PersistedGroup(
            primary = PersistedStation.from(group.primary),
            backups = group.backups.map(PersistedStation::from),
        )
    }
}

@Serializable
private data class PersistedRoute(
    val id: String,
    val name: String,
    val borrow: PersistedGroup,
    val returns: PersistedGroup,
    val departure: String,
    val weekdays: List<String>,
    val revision: Long,
    val reminderMinutesBefore: Int?,
    val lowBikeThreshold: Int,
    val lowDockThreshold: Int,
    val estimatedReturnMinutes: Int?,
    val bikeDisplay: BikeKind,
    val bikeThreshold: BikeKind,
) {
    fun toDomain() = CommuteRoute(
        id = UUID.fromString(id),
        name = name,
        borrow = borrow.toDomain(),
        returns = returns.toDomain(),
        departure = LocalTime.parse(departure),
        weekdays = weekdays.mapTo(linkedSetOf(), DayOfWeek::valueOf),
        revision = revision,
        reminderMinutesBefore = reminderMinutesBefore,
        lowBikeThreshold = lowBikeThreshold,
        lowDockThreshold = lowDockThreshold,
        estimatedReturnMinutes = estimatedReturnMinutes,
        bikeDisplay = bikeDisplay,
        bikeThreshold = bikeThreshold,
    )

    companion object {
        fun from(route: CommuteRoute) = PersistedRoute(
            id = route.id.toString(),
            name = route.name,
            borrow = PersistedGroup.from(route.borrow),
            returns = PersistedGroup.from(route.returns),
            departure = route.departure.toString(),
            weekdays = route.weekdays.sortedBy(DayOfWeek::getValue).map(DayOfWeek::name),
            revision = route.revision,
            reminderMinutesBefore = route.reminderMinutesBefore,
            lowBikeThreshold = route.lowBikeThreshold,
            lowDockThreshold = route.lowDockThreshold,
            estimatedReturnMinutes = route.estimatedReturnMinutes,
            bikeDisplay = route.bikeDisplay,
            bikeThreshold = route.bikeThreshold,
        )
    }
}

@Serializable
private data class PersistedSession(
    val id: String,
    val routeSnapshot: PersistedRoute,
    val phase: String,
    val selectedBorrow: PersistedStation,
    val selectedReturn: PersistedStation,
    val actualBorrow: PersistedStation?,
    val actualReturn: PersistedStation?,
    val startedAt: String,
    val borrowedAt: String?,
    val returnedAt: String?,
    val revision: Long,
) {
    fun toDomain(): CommuteSession {
        return CommuteSession(
            id = UUID.fromString(id),
            routeSnapshot = routeSnapshot.toDomain(),
            phase = CommutePhase.valueOf(phase),
            selectedBorrow = selectedBorrow.toDomain(),
            selectedReturn = selectedReturn.toDomain(),
            actualBorrow = actualBorrow?.toDomain(),
            actualReturn = actualReturn?.toDomain(),
            startedAt = Instant.parse(startedAt),
            borrowedAt = borrowedAt?.let(Instant::parse),
            returnedAt = returnedAt?.let(Instant::parse),
            revision = revision,
        )
    }

    companion object {
        fun from(session: CommuteSession) = PersistedSession(
            id = session.id.toString(),
            routeSnapshot = PersistedRoute.from(session.routeSnapshot),
            phase = session.phase.name,
            selectedBorrow = PersistedStation.from(session.selectedBorrow),
            selectedReturn = PersistedStation.from(session.selectedReturn),
            actualBorrow = session.actualBorrow?.let(PersistedStation::from),
            actualReturn = session.actualReturn?.let(PersistedStation::from),
            startedAt = session.startedAt.toString(),
            borrowedAt = session.borrowedAt?.toString(),
            returnedAt = session.returnedAt?.toString(),
            revision = session.revision,
        )
    }
}
