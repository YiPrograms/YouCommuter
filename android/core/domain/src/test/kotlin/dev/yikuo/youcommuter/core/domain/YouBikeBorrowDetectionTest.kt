package dev.yikuo.youcommuter.core.domain

import dev.yikuo.youcommuter.core.model.BikeKind
import dev.yikuo.youcommuter.core.model.CommutePhase
import dev.yikuo.youcommuter.core.model.CommuteRoute
import dev.yikuo.youcommuter.core.model.RouteStation
import dev.yikuo.youcommuter.core.model.StationGroup
import dev.yikuo.youcommuter.core.model.StationId
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class YouBikeBorrowDetectionTest {
    private val at = Instant.parse("2026-10-21T00:48:07.630149Z")
    private val borrow = RouteStation(StationId("borrow"), "Borrow")
    private val backup = RouteStation(StationId("backup"), "Backup")
    private val returns = RouteStation(StationId("return"), "Return")
    private val route = CommuteRoute(UUID.randomUUID(), "Work", StationGroup(borrow, listOf(backup)),
        StationGroup(returns), LocalTime.of(8, 30), emptySet(), 1, bikeDisplay = BikeKind.Regular)

    @Test
    fun `EYB connection confirms an ordinary-bike commute immediately at connection time`() {
        val initial = CommuteReducer.start(route, UUID.randomUUID(), at)
        val connectedAt = at.plusMillis(7_033)
        val command = checkNotNull(YouBikeBorrowDetection.borrowCommand(initial, "EYB12345678", connectedAt))

        val riding = CommuteReducer.reduce(initial, initial.id, command)

        assertEquals(CommutePhase.Riding, riding.phase)
        assertEquals(connectedAt, riding.borrowedAt)
        assertEquals(borrow, riding.actualBorrow)
        assertEquals(returns, riding.selectedReturn)
        assertEquals(BikeKind.Regular, riding.routeSnapshot.bikeDisplay)
    }

    @Test
    fun `matching is prefix based with no assumed numeric suffix or electric-bike classification`() {
        for (name in listOf("EYB", "EYB12345678", "EYB-controller")) assertTrue(YouBikeBorrowDetection.matches(name))
        for (name in listOf(null, "", "Headphones", "Watch EYB", "eyb12345678")) assertFalse(YouBikeBorrowDetection.matches(name))
    }

    @Test
    fun `connection records the selected backup and preserves the selected return station`() {
        val initial = CommuteReducer.start(route, UUID.randomUUID(), at).copy(selectedBorrow = backup)
        val command = checkNotNull(YouBikeBorrowDetection.borrowCommand(initial, "EYB12345678", at.plusSeconds(1)))

        val riding = CommuteReducer.reduce(initial, initial.id, command)

        assertEquals(backup, riding.actualBorrow)
        assertEquals(returns, riding.selectedReturn)
    }

    @Test
    fun `only a commute awaiting borrowing accepts a matching connection`() {
        val initial = CommuteReducer.start(route, UUID.randomUUID(), at)
        for (phase in CommutePhase.entries) {
            val command = YouBikeBorrowDetection.borrowCommand(initial.copy(phase = phase), "EYB12345678", at.plusSeconds(1))
            assertEquals(phase.name, phase in setOf(CommutePhase.Preparing, CommutePhase.WalkingToBorrow), command != null)
        }
        assertNull(YouBikeBorrowDetection.borrowCommand(initial, "Headphones", at.plusSeconds(1)))
        assertNull(YouBikeBorrowDetection.borrowCommand(initial, null, at.plusSeconds(1)))
        assertNull(YouBikeBorrowDetection.borrowCommand(initial, "EYB12345678", at.minusSeconds(1)))
    }

    @Test
    fun `duplicate connections do not update borrowing time or create a second transition`() {
        val initial = CommuteReducer.start(route, UUID.randomUUID(), at)
        val first = checkNotNull(YouBikeBorrowDetection.borrowCommand(initial, "EYB12345678", at.plusSeconds(1)))
        val duplicate = checkNotNull(YouBikeBorrowDetection.borrowCommand(initial, "EYB12345678", at.plusSeconds(2)))
        val riding = CommuteReducer.reduce(initial, initial.id, first)

        assertThrows(StaleCommuteCommand::class.java) { CommuteReducer.reduce(riding, initial.id, duplicate) }
        assertNull(YouBikeBorrowDetection.borrowCommand(riding, "EYB12345678", at.plusSeconds(3)))
        assertEquals(at.plusSeconds(1), riding.borrowedAt)
    }

    @Test
    fun `queued connection cannot borrow in a replacement session with the same revision`() {
        val initial = CommuteReducer.start(route, UUID.randomUUID(), at)
        val command = checkNotNull(YouBikeBorrowDetection.borrowCommand(initial, "EYB12345678", at.plusSeconds(1)))
        val replacement = CommuteReducer.start(route, UUID.randomUUID(), at.plusSeconds(2))

        assertEquals(initial.revision, replacement.revision)
        assertThrows(StaleCommuteCommand::class.java) { CommuteReducer.reduce(replacement, initial.id, command) }
        assertEquals(CommutePhase.WalkingToBorrow, replacement.phase)
    }
}
