package dev.yikuo.youcommuter.core.domain

import dev.yikuo.youcommuter.core.model.CommutePhase
import dev.yikuo.youcommuter.core.model.CommuteRoute
import dev.yikuo.youcommuter.core.model.RouteStation
import dev.yikuo.youcommuter.core.model.StationGroup
import dev.yikuo.youcommuter.core.model.StationId
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CommuteReducerTest {
    private val borrow = RouteStation(StationId("borrow"), "Borrow station")
    private val returns = RouteStation(StationId("return"), "Return station")
    private val route = CommuteRoute(
        id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        name = "Work",
        borrow = StationGroup(borrow),
        returns = StationGroup(returns),
        departure = LocalTime.of(8, 30),
        weekdays = setOf(DayOfWeek.MONDAY),
        revision = 3,
    )
    private val start = Instant.parse("2026-10-02T00:00:00Z")

    @Test
    fun `start snapshots route and enters walking phase`() {
        val session = CommuteReducer.start(route, UUID.randomUUID(), start)

        assertEquals(CommutePhase.WalkingToBorrow, session.phase)
        assertEquals(route, session.routeSnapshot)
        assertEquals(borrow, session.selectedBorrow)
        assertEquals(returns, session.selectedReturn)
        assertEquals(1, session.revision)
        assertNull(session.actualBorrow)
    }

    @Test
    fun `borrow then return completes the commute`() {
        val initial = CommuteReducer.start(route, UUID.randomUUID(), start)
        val borrowedAt = start.plusSeconds(300)
        val borrowed = CommuteReducer.reduce(
            initial, initial.id,
            CommuteCommand.ConfirmBorrowed(UUID.randomUUID(), 1, borrowedAt, borrow),
        )
        val returnedAt = start.plusSeconds(900)
        val completed = CommuteReducer.reduce(
            borrowed, borrowed.id,
            CommuteCommand.ConfirmReturned(UUID.randomUUID(), 2, returnedAt, returns),
        )

        assertEquals(CommutePhase.Completed, completed.phase)
        assertEquals(borrow, completed.actualBorrow)
        assertEquals(returns, completed.actualReturn)
        assertEquals(borrowedAt, completed.borrowedAt)
        assertEquals(returnedAt, completed.returnedAt)
        assertEquals(3, completed.revision)
    }

    @Test
    fun `return before borrow is rejected`() {
        val initial = CommuteReducer.start(route, UUID.randomUUID(), start)

        assertThrows(InvalidCommuteTransition::class.java) {
            CommuteReducer.reduce(
                initial, initial.id,
                CommuteCommand.ConfirmReturned(UUID.randomUUID(), 1, start.plusSeconds(60), returns),
            )
        }
    }

    @Test
    fun `stale command is rejected`() {
        val initial = CommuteReducer.start(route, UUID.randomUUID(), start)

        assertThrows(StaleCommuteCommand::class.java) {
            CommuteReducer.reduce(
                initial, initial.id,
                CommuteCommand.ConfirmBorrowed(UUID.randomUUID(), 99, start.plusSeconds(60), borrow),
            )
        }
    }

    @Test
    fun `terminal commute cannot transition again`() {
        val initial = CommuteReducer.start(route, UUID.randomUUID(), start)
        val cancelled = CommuteReducer.reduce(
            initial, initial.id,
            CommuteCommand.Cancel(UUID.randomUUID(), 1, start.plusSeconds(60)),
        )

        assertThrows(InvalidCommuteTransition::class.java) {
            CommuteReducer.reduce(
                cancelled, cancelled.id,
                CommuteCommand.ConfirmBorrowed(UUID.randomUUID(), 2, start.plusSeconds(120), borrow),
            )
        }
    }

    @Test
    fun `saved backup can become the active borrow target`() {
        val backup = RouteStation(StationId("backup"), "Backup station")
        val routeWithBackup = route.copy(borrow = StationGroup(borrow, listOf(backup)))
        val initial = CommuteReducer.start(routeWithBackup, UUID.randomUUID(), start)

        val selected = CommuteReducer.reduce(
            initial, initial.id,
            CommuteCommand.SelectBorrowStation(UUID.randomUUID(), 1, start.plusSeconds(30), backup),
        )

        assertEquals(backup, selected.selectedBorrow)
        assertEquals(backup, selected.currentTarget())
        assertEquals(2, selected.revision)
    }

    @Test
    fun `return backup can be selected before borrowing and is retained while riding`() {
        val backup = RouteStation(StationId("backup"), "Return backup")
        val initial = CommuteReducer.start(route.copy(returns = StationGroup(returns, listOf(backup))), UUID.randomUUID(), start)
        for (phase in listOf(CommutePhase.Preparing, CommutePhase.WalkingToBorrow)) {
            val selected = CommuteReducer.reduce(initial.copy(phase = phase), initial.id,
                CommuteCommand.SelectReturnStation(UUID.randomUUID(), 1, start.plusSeconds(30), backup))
            val riding = CommuteReducer.reduce(selected, selected.id,
                CommuteCommand.ConfirmBorrowed(UUID.randomUUID(), 2, start.plusSeconds(60), borrow))
            assertEquals(backup, riding.selectedReturn)
            assertEquals(backup, riding.currentTarget())
        }
    }

    @Test
    fun `station outside the route cannot become a target`() {
        val initial = CommuteReducer.start(route, UUID.randomUUID(), start)
        val unknown = RouteStation(StationId("unknown"), "Unknown")

        assertThrows(InvalidCommuteTransition::class.java) {
            CommuteReducer.reduce(
                initial, initial.id,
                CommuteCommand.SelectBorrowStation(UUID.randomUUID(), 1, start.plusSeconds(30), unknown),
            )
        }
    }
}
