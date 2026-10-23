package dev.yikuo.youcommuter.observation

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BorrowObservationLogTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val startedAt = Instant.parse("2026-10-19T10:00:00Z")

    private fun log(eventLimit: Int = 200, deviceLimit: Int = 64) =
        BorrowObservationLog(id, sessionId, startedAt, 1_000, eventLimit, deviceLimit)

    @Test
    fun `late callbacks and stop from a prior observation do not change a new capture`() {
        val log = log()
        val oldId = UUID.fromString("00000000-0000-0000-0000-000000000003")
        assertFalse(log.record(oldId, 2_000, ObservationEventKind.Connected, "device-a"))
        assertFalse(log.stop(oldId, 2_000, ObservationEventKind.Stopped))
        assertTrue(log.running)
        assertTrue(log.events().isEmpty())
    }

    @Test
    fun `connection and disconnect share an anonymous device without inferring a rental`() {
        val log = log()
        log.record(id, 1_100, ObservationEventKind.Connected, "AA:BB:CC:DD:EE:FF", ObservationTransport.LowEnergy)
        log.record(id, 1_200, ObservationEventKind.Disconnected, "AA:BB:CC:DD:EE:FF", ObservationTransport.LowEnergy)
        assertEquals(listOf(1, 1), log.events().map { it.device })
        assertEquals(listOf(ObservationEventKind.Connected, ObservationEventKind.Disconnected), log.events().map { it.kind })
        assertTrue(log.events().none { it.toString().contains("AA:BB") })
        assertTrue(log.running)
    }

    @Test
    fun `wall clock changes cannot reverse elapsed event times`() {
        val log = log()
        log.record(id, 900, ObservationEventKind.Started)
        log.record(id, 1_500, ObservationEventKind.BorrowSucceeded)
        log.record(id, 1_100, ObservationEventKind.Disconnected, "device-a")
        assertEquals(listOf(0L, 500L, 500L), log.events().map { it.elapsedMillis })
        assertEquals(startedAt, log.startedAt)
    }

    @Test
    fun `a busy radio environment has bounded events with stable sequence numbers`() {
        val log = log(eventLimit = 3)
        repeat(10) { log.record(id, 1_000L + it, ObservationEventKind.Connected, "device-a") }
        assertEquals(listOf(8L, 9L, 10L), log.events().map { it.number })
    }

    @Test
    fun `device limit does not discard events from an already seen device or user markers`() {
        val log = log(deviceLimit = 1)
        assertTrue(log.record(id, 1_001, ObservationEventKind.Connected, "device-a"))
        assertFalse(log.record(id, 1_002, ObservationEventKind.Connected, "device-b"))
        assertTrue(log.record(id, 1_003, ObservationEventKind.Disconnected, "device-a"))
        assertTrue(log.record(id, 1_004, ObservationEventKind.BorrowFailed))
        assertEquals(3, log.events().size)
    }

    @Test
    fun `stopping retains the final reason and rejects later callbacks and duplicate stops`() {
        val log = log()
        assertTrue(log.stop(id, 2_000, ObservationEventKind.PermissionLost))
        assertFalse(log.record(id, 3_000, ObservationEventKind.Connected, "device-a"))
        assertFalse(log.stop(id, 4_000, ObservationEventKind.Stopped))
        assertFalse(log.running)
        assertEquals(ObservationEventKind.PermissionLost, log.events().single().kind)
    }

    @Test
    fun `previously published events remain unchanged as capture continues`() {
        val log = log()
        log.record(id, 1_100, ObservationEventKind.Started)
        val snapshot = log.events()
        log.record(id, 1_200, ObservationEventKind.ReturnSucceeded)
        assertEquals(1, snapshot.size)
        assertEquals(2, log.events().size)
    }
}
