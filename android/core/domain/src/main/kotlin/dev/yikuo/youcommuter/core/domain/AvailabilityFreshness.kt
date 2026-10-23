package dev.yikuo.youcommuter.core.domain

import dev.yikuo.youcommuter.core.model.Freshness
import java.time.Duration
import java.time.Instant

/** Pure rule shared with the backend. Callers supply time for reproducible behavior. */
object AvailabilityFreshness {
    fun classify(observedAt: Instant?, now: Instant, cadence: Duration): Freshness {
        if (observedAt == null || cadence <= Duration.ZERO || observedAt > now.plusSeconds(60)) {
            return Freshness.Unavailable
        }
        val age = maxOf(Duration.ZERO, Duration.between(observedAt, now))
        val freshLimit = maxOf(Duration.ofMinutes(2), cadence.multipliedBy(2).plusSeconds(30))
        val staleLimit = maxOf(Duration.ofMinutes(10), cadence.multipliedBy(3))
        return when {
            age <= freshLimit -> Freshness.Fresh
            age <= staleLimit -> Freshness.Aging
            else -> Freshness.Stale
        }
    }
}
