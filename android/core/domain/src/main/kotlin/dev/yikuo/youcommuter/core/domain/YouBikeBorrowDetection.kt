package dev.yikuo.youcommuter.core.domain

import dev.yikuo.youcommuter.core.model.CommutePhase
import dev.yikuo.youcommuter.core.model.CommuteSession
import java.time.Instant
import java.util.UUID

/** Product rule: a new EYB connection confirms borrowing; the prefix does not identify bike type. */
object YouBikeBorrowDetection {
    fun matches(deviceName: String?): Boolean = deviceName?.startsWith("EYB") == true

    fun borrowCommand(session: CommuteSession, deviceName: String?, at: Instant): CommuteCommand.ConfirmBorrowed? {
        if (!matches(deviceName) || !session.phase.awaitingBorrow || at < session.startedAt) return null
        return CommuteCommand.ConfirmBorrowed(UUID.randomUUID(), session.revision, at, session.selectedBorrow)
    }
}

val CommutePhase.awaitingBorrow: Boolean
    get() = this == CommutePhase.Preparing || this == CommutePhase.WalkingToBorrow
