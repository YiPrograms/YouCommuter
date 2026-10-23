package dev.yikuo.youcommuter.observation

import java.time.Instant
import java.util.UUID

internal enum class ObservationEventKind {
    Started, InitialConnection, Connected, Disconnected, BluetoothOff,
    BorrowSucceeded, BorrowFailed, TemporaryLock, ReturnSucceeded,
    PhaseChanged, SnapshotFailed, PermissionLost, Stopped, TimedOut, SessionEnded, Failed,
}

internal enum class ObservationTransport { Unknown, Classic, LowEnergy }

internal data class ObservationEvent(
    val number: Long,
    val elapsedMillis: Long,
    val kind: ObservationEventKind,
    val device: Int? = null,
    val transport: ObservationTransport = ObservationTransport.Unknown,
    val deviceInfo: ObservationDeviceInfo? = null,
)

/** Bounded field notes, deliberately separate from the commute state machine. */
internal class BorrowObservationLog(
    val id: UUID,
    val sessionId: UUID?,
    val startedAt: Instant,
    private val startedElapsedMillis: Long,
    private val eventLimit: Int = 200,
    private val deviceLimit: Int = 64,
) {
    init { require(eventLimit > 0 && deviceLimit > 0) }

    private val devices = linkedMapOf<String, Int>()
    private val entries = ArrayDeque<ObservationEvent>()
    private var sequence = 0L
    private var lastElapsedMillis = 0L
    var running: Boolean = true
        private set

    fun events(): List<ObservationEvent> = entries.toList()

    fun recordLink(observationId: UUID, link: BluetoothLinkObservation): Boolean = record(
        observationId, link.receivedElapsedRealtimeMillis, link.kind, link.address, link.transport, link.deviceInfo,
    )

    /** Raw addresses are used only as in-memory keys; none leave this object. */
    fun record(
        observationId: UUID,
        elapsedRealtimeMillis: Long,
        kind: ObservationEventKind,
        address: String? = null,
        transport: ObservationTransport = ObservationTransport.Unknown,
        deviceInfo: ObservationDeviceInfo? = null,
    ): Boolean {
        if (observationId != id || !running) return false
        val device = if (address == null) null else devices[address] ?: run {
            if (devices.size >= deviceLimit) return false
            (devices.size + 1).also { devices[address] = it }
        }
        lastElapsedMillis = maxOf(lastElapsedMillis, elapsedRealtimeMillis - startedElapsedMillis, 0)
        // Each event retains its own snapshot, including null UUIDs versus a known empty cache.
        val snapshot = deviceInfo?.takeIf { device != null }?.copy(cachedServiceUuids = deviceInfo.cachedServiceUuids?.toList())
        entries.addLast(ObservationEvent(++sequence, lastElapsedMillis, kind, device, transport, snapshot))
        while (entries.size > eventLimit) entries.removeFirst()
        return true
    }

    fun stop(observationId: UUID, elapsedRealtimeMillis: Long, reason: ObservationEventKind): Boolean {
        require(reason in stopReasons)
        if (!record(observationId, elapsedRealtimeMillis, reason)) return false
        running = false
        devices.clear()
        return true
    }

    companion object {
        private val stopReasons = setOf(
            ObservationEventKind.Stopped, ObservationEventKind.TimedOut, ObservationEventKind.SessionEnded,
            ObservationEventKind.PermissionLost, ObservationEventKind.BluetoothOff, ObservationEventKind.Failed,
        )
    }
}
