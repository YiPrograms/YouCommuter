package dev.yikuo.youcommuter.core.domain

import dev.yikuo.youcommuter.core.model.CommuteRoute
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class ReminderOccurrence(
    val routeId: UUID,
    val routeRevision: Long,
    val localDate: LocalDate,
    val departureAt: Instant,
    val reminderAt: Instant,
)

fun upcomingReminderOccurrences(
    route: CommuteRoute,
    now: Instant,
    zoneId: ZoneId = ZoneId.of("Asia/Taipei"),
    horizonDays: Long = 7,
): List<ReminderOccurrence> {
    val leadMinutes = route.reminderMinutesBefore ?: return emptyList()
    if (route.weekdays.isEmpty() || horizonDays <= 0) return emptyList()
    val firstDate = now.atZone(zoneId).toLocalDate()
    return (0L until horizonDays).mapNotNull { offset ->
        val date = firstDate.plusDays(offset)
        if (date.dayOfWeek !in route.weekdays) return@mapNotNull null
        val departure = date.atTime(route.departure).atZone(zoneId).toInstant()
        val reminder = departure.minusSeconds(leadMinutes * 60L)
        if (reminder <= now) return@mapNotNull null
        ReminderOccurrence(
            routeId = route.id,
            routeRevision = route.revision,
            localDate = date,
            departureAt = departure,
            reminderAt = reminder,
        )
    }
}
