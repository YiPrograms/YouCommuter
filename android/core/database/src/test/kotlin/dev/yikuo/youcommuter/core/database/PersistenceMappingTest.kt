package dev.yikuo.youcommuter.core.database

import dev.yikuo.youcommuter.core.model.BikeKind
import dev.yikuo.youcommuter.core.model.CommutePhase
import dev.yikuo.youcommuter.core.model.CommuteRoute
import dev.yikuo.youcommuter.core.model.CommuteSession
import dev.yikuo.youcommuter.core.model.RouteStation
import dev.yikuo.youcommuter.core.model.StationGroup
import dev.yikuo.youcommuter.core.model.StationId
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class PersistenceMappingTest {
    private val route = CommuteRoute(
        id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        name = "Home to office",
        borrow = StationGroup(
            RouteStation(StationId("borrow"), "Borrow"),
            listOf(RouteStation(StationId("backup"), "Backup")),
        ),
        returns = StationGroup(RouteStation(StationId("return"), "Return")),
        departure = LocalTime.of(8, 30),
        weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
        revision = 2,
        reminderMinutesBefore = 15,
        lowBikeThreshold = 4,
        lowDockThreshold = 1,
        estimatedReturnMinutes = 25,
        bikeDisplay = BikeKind.All, bikeThreshold = BikeKind.Electric,
    )
    private val mapping = PersistenceMapping()

    @Test
    fun `route survives persistence round trip`() {
        assertEquals(route, mapping.routeFromEntity(mapping.routeToEntity(route)))
    }

    @Test
    fun `active session survives persistence round trip`() {
        val session = CommuteSession(
            id = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            routeSnapshot = route,
            phase = CommutePhase.Riding,
            selectedBorrow = route.borrow.backups.first(),
            selectedReturn = route.returns.primary,
            actualBorrow = route.borrow.primary,
            actualReturn = null,
            startedAt = Instant.parse("2026-10-02T00:00:00Z"),
            borrowedAt = Instant.parse("2026-10-02T00:05:00Z"),
            returnedAt = null,
            revision = 2,
        )

        val entity = mapping.sessionToEntity(session, UUID.randomUUID())

        assertEquals(session, mapping.sessionFromEntity(entity))
    }

    @Test
    fun `stored route requires every current preference field`() {
        val entity = mapping.routeToEntity(route)
        val payload = Json.parseToJsonElement(entity.payload).jsonObject
        for (field in listOf("lowBikeThreshold", "lowDockThreshold", "bikeDisplay", "bikeThreshold", "estimatedReturnMinutes", "reminderMinutesBefore")) {
            val incomplete = entity.copy(payload = JsonObject(payload - field).toString())
            assertThrows(SerializationException::class.java) { mapping.routeFromEntity(incomplete) }
        }
    }

    @Test
    fun `stored session requires explicit selected stations`() {
        val session = CommuteSession(UUID.randomUUID(), route, CommutePhase.WalkingToBorrow,
            route.borrow.backups.first(), route.returns.primary, null, null,
            Instant.parse("2026-10-21T00:00:00Z"), null, null, 1)
        val entity = mapping.sessionToEntity(session, null)
        val payload = Json.parseToJsonElement(entity.payload).jsonObject
        for (field in listOf("selectedBorrow", "selectedReturn", "routeSnapshot")) {
            val incomplete = entity.copy(payload = JsonObject(payload - field).toString())
            assertThrows(SerializationException::class.java) { mapping.sessionFromEntity(incomplete) }
        }
    }
}
