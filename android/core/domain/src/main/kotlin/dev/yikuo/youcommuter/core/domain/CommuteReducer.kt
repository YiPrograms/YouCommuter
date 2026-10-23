package dev.yikuo.youcommuter.core.domain

import dev.yikuo.youcommuter.core.model.CommutePhase
import dev.yikuo.youcommuter.core.model.CommuteRoute
import dev.yikuo.youcommuter.core.model.CommuteSession
import dev.yikuo.youcommuter.core.model.RouteStation
import dev.yikuo.youcommuter.core.model.isTerminal
import java.time.Instant
import java.util.UUID

sealed interface CommuteCommand {
    val id: UUID
    val expectedRevision: Long
    val at: Instant

    data class ConfirmBorrowed(
        override val id: UUID,
        override val expectedRevision: Long,
        override val at: Instant,
        val station: RouteStation,
    ) : CommuteCommand

    data class ConfirmReturned(
        override val id: UUID,
        override val expectedRevision: Long,
        override val at: Instant,
        val station: RouteStation,
    ) : CommuteCommand

    data class Cancel(
        override val id: UUID,
        override val expectedRevision: Long,
        override val at: Instant,
    ) : CommuteCommand

    data class SelectBorrowStation(
        override val id: UUID,
        override val expectedRevision: Long,
        override val at: Instant,
        val station: RouteStation,
    ) : CommuteCommand

    data class SelectReturnStation(
        override val id: UUID,
        override val expectedRevision: Long,
        override val at: Instant,
        val station: RouteStation,
    ) : CommuteCommand
}

class InvalidCommuteTransition(message: String) : IllegalStateException(message)
class StaleCommuteCommand : IllegalStateException("The commute changed before this action was applied")

object CommuteReducer {
    fun start(route: CommuteRoute, sessionId: UUID, at: Instant) = CommuteSession(
        id = sessionId,
        routeSnapshot = route,
        phase = CommutePhase.WalkingToBorrow,
        selectedBorrow = route.borrow.primary,
        selectedReturn = route.returns.primary,
        actualBorrow = null,
        actualReturn = null,
        startedAt = at,
        borrowedAt = null,
        returnedAt = null,
        revision = 1,
    )

    fun reduce(session: CommuteSession, targetSessionId: UUID, command: CommuteCommand): CommuteSession {
        if (targetSessionId != session.id || command.expectedRevision != session.revision) throw StaleCommuteCommand()
        if (session.phase.isTerminal) throw InvalidCommuteTransition("This commute is already finished")
        return when (command) {
            is CommuteCommand.ConfirmBorrowed -> {
                if (session.phase !in setOf(CommutePhase.Preparing, CommutePhase.WalkingToBorrow)) {
                    throw InvalidCommuteTransition("A bike can only be confirmed before riding")
                }
                if (command.station.id != session.selectedBorrow.id) {
                    throw InvalidCommuteTransition("Confirm the currently selected borrow station")
                }
                session.copy(
                    phase = CommutePhase.Riding,
                    actualBorrow = command.station,
                    borrowedAt = command.at,
                    revision = session.revision + 1,
                )
            }
            is CommuteCommand.ConfirmReturned -> {
                if (session.phase !in setOf(CommutePhase.Riding, CommutePhase.NearReturn)) {
                    throw InvalidCommuteTransition("A return can only be confirmed while riding")
                }
                if (command.station.id != session.selectedReturn.id) {
                    throw InvalidCommuteTransition("Confirm the currently selected return station")
                }
                session.copy(
                    phase = CommutePhase.Completed,
                    actualReturn = command.station,
                    returnedAt = command.at,
                    revision = session.revision + 1,
                )
            }
            is CommuteCommand.Cancel -> session.copy(
                phase = CommutePhase.Cancelled,
                revision = session.revision + 1,
            )
            is CommuteCommand.SelectBorrowStation -> {
                if (session.phase !in setOf(CommutePhase.Preparing, CommutePhase.WalkingToBorrow)) {
                    throw InvalidCommuteTransition("The borrow station cannot be changed after borrowing")
                }
                if (session.routeSnapshot.borrow.all.none { it.id == command.station.id }) {
                    throw InvalidCommuteTransition("The station is not part of this route")
                }
                session.copy(selectedBorrow = command.station, revision = session.revision + 1)
            }
            is CommuteCommand.SelectReturnStation -> {
                if (session.phase !in setOf(CommutePhase.Preparing, CommutePhase.WalkingToBorrow,
                        CommutePhase.Riding, CommutePhase.NearReturn)) {
                    throw InvalidCommuteTransition("The return station can only be changed during an active commute")
                }
                if (session.routeSnapshot.returns.all.none { it.id == command.station.id }) {
                    throw InvalidCommuteTransition("The station is not part of this route")
                }
                session.copy(selectedReturn = command.station, revision = session.revision + 1)
            }
        }
    }
}

val dev.yikuo.youcommuter.core.model.StationGroup.all: List<RouteStation>
    get() = listOf(primary) + backups

fun CommuteSession.currentTarget(): RouteStation? = when (phase) {
    CommutePhase.Preparing, CommutePhase.WalkingToBorrow -> selectedBorrow
    CommutePhase.Riding, CommutePhase.NearReturn -> selectedReturn
    else -> null
}
