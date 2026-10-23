package dev.yikuo.youcommuter.observation

import android.os.SystemClock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ObservationStatus { Idle, Starting, Running, Stopped, TimedOut, SessionEnded, PermissionLost, BluetoothOff, Failed }

internal data class BorrowObservationState(
    val id: UUID? = null,
    val sessionId: UUID? = null,
    val status: ObservationStatus = ObservationStatus.Idle,
    val events: List<ObservationEvent> = emptyList(),
) {
    val active: Boolean get() = status == ObservationStatus.Starting || status == ObservationStatus.Running
}

/** Main-thread-owned, process-local data. Process death ends observation rather than resuming it. */
internal object BorrowObservationStore {
    private var log: BorrowObservationLog? = null
    private val mutableState = MutableStateFlow(BorrowObservationState())
    val state = mutableState.asStateFlow()

    fun begin(sessionId: UUID?): UUID? {
        if (mutableState.value.active) return null
        val next = BorrowObservationLog(UUID.randomUUID(), sessionId, Instant.now(), SystemClock.elapsedRealtime())
        log = next
        next.record(next.id, SystemClock.elapsedRealtime(), ObservationEventKind.Started)
        publish(ObservationStatus.Starting)
        return next.id
    }

    fun running(id: UUID) {
        if (log?.id == id && log?.running == true) publish(ObservationStatus.Running)
    }

    fun record(id: UUID, kind: ObservationEventKind) {
        if (log?.record(id, SystemClock.elapsedRealtime(), kind) == true) publish()
    }

    fun recordLink(id: UUID, link: BluetoothLinkObservation) {
        if (log?.recordLink(id, link) == true) publish()
    }

    fun stop(id: UUID, status: ObservationStatus, reason: ObservationEventKind) {
        if (log?.stop(id, SystemClock.elapsedRealtime(), reason) == true) publish(status)
    }

    fun clear() {
        if (mutableState.value.active) return
        log = null
        mutableState.value = BorrowObservationState()
    }

    /** User-initiated export includes cached names, but no addresses, commute IDs or radio payloads. */
    fun export(): String? {
        val current = log ?: return null
        return exportBorrowObservation(current.startedAt, mutableState.value.status, current.events())
    }

    private fun publish(status: ObservationStatus = mutableState.value.status) {
        val current = log ?: return
        mutableState.value = BorrowObservationState(current.id, current.sessionId, status, current.events())
    }
}
