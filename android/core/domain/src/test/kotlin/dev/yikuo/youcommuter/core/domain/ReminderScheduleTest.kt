package dev.yikuo.youcommuter.core.domain

import dev.yikuo.youcommuter.core.model.CommuteRoute
import dev.yikuo.youcommuter.core.model.RouteStation
import dev.yikuo.youcommuter.core.model.StationGroup
import dev.yikuo.youcommuter.core.model.StationId
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderScheduleTest {
    private val route = CommuteRoute(
        id = UUID.fromString("00000000-0000-0000-0000-000000000010"),
        name = "Work",
        borrow = StationGroup(RouteStation(StationId("borrow"), "Borrow")),
        returns = StationGroup(RouteStation(StationId("return"), "Return")),
        departure = LocalTime.of(8, 30),
        weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
        revision = 4,
        reminderMinutesBefore = 15,
    )
    private val zone = ZoneId.of("Asia/Taipei")

    @Test
    fun `generates selected weekdays in Taiwan time`() {
        val now = Instant.parse("2026-10-04T00:00:00Z") // Sunday 08:00 in Taipei

        val occurrences = upcomingReminderOccurrences(route, now, zone)

        assertEquals(listOf(LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-06")), occurrences.map { it.localDate })
        assertEquals(Instant.parse("2026-10-05T00:15:00Z"), occurrences.first().reminderAt)
        assertEquals(Instant.parse("2026-10-05T00:30:00Z"), occurrences.first().departureAt)
    }

    @Test
    fun `disabled reminders and manual-only schedules produce no work`() {
        val now = Instant.parse("2026-10-04T00:00:00Z")

        assertTrue(upcomingReminderOccurrences(route.copy(reminderMinutesBefore = null), now, zone).isEmpty())
        assertTrue(upcomingReminderOccurrences(route.copy(weekdays = emptySet()), now, zone).isEmpty())
    }

    @Test
    fun `past reminder on the current date is not generated`() {
        val mondayAfterDeparture = Instant.parse("2026-10-05T01:00:00Z")

        val occurrences = upcomingReminderOccurrences(route, mondayAfterDeparture, zone)

        assertTrue(occurrences.none { it.localDate == LocalDate.parse("2026-10-05") })
    }
}
