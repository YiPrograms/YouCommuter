package dev.yikuo.youcommuter.observation

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BorrowObservationExportTest {
    @Test
    fun `export preserves per-event cache snapshots and original link timestamps without addresses`() {
        val id = UUID.randomUUID()
        val at = Instant.parse("2026-10-20T00:00:00Z")
        val log = BorrowObservationLog(id, UUID.randomUUID(), at, 1_000)
        val uuid = "00001800-0000-1000-8000-00805f9b34fb"
        val cache = mutableListOf(uuid)
        val info = ObservationDeviceInfo("Test \"device\"", cache, ObservationDeviceType.Dual, 0x0104)
        log.recordLink(id, BluetoothLinkObservation(ObservationEventKind.Connected, "AA:BB:CC:DD:EE:FF",
            ObservationTransport.LowEnergy, 1_100, info))
        cache.clear()
        log.recordLink(id, BluetoothLinkObservation(ObservationEventKind.Disconnected, "AA:BB:CC:DD:EE:FF",
            ObservationTransport.LowEnergy, 11_600, info.copy(cachedServiceUuids = null)))
        log.record(id, 11_700, ObservationEventKind.BorrowSucceeded)
        val exported = exportBorrowObservation(at, ObservationStatus.Stopped, log.events())
        val body = Json.parseToJsonElement(exported).jsonObject
        val events = body.getValue("events").jsonArray.map { it.jsonObject }
        val device = events[0].getValue("deviceInfo").jsonObject
        assertEquals("2", body.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(at.toString(), body.getValue("startedAt").jsonPrimitive.content)
        assertEquals("100", events[0].getValue("elapsedMillis").jsonPrimitive.content)
        assertEquals("10600", events[1].getValue("elapsedMillis").jsonPrimitive.content)
        assertEquals("1", events[0].getValue("device").jsonPrimitive.content)
        assertEquals(events[0].getValue("device"), events[1].getValue("device"))
        assertEquals("Test \"device\"", device.getValue("name").jsonPrimitive.content)
        assertEquals("Dual", device.getValue("type").jsonPrimitive.content)
        assertEquals("260", device.getValue("bluetoothClass").jsonPrimitive.content)
        assertEquals(uuid, device.getValue("cachedServiceUuids").jsonArray.single().jsonPrimitive.content)
        assertEquals(JsonNull, events[1].getValue("deviceInfo").jsonObject.getValue("cachedServiceUuids"))
        assertEquals(JsonNull, events[2].getValue("deviceInfo"))
        assertFalse(exported.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(exported.contains(id.toString()))
    }

    @Test
    fun `export keeps missing names classes and unknown versus empty UUIDs explicit`() {
        val unknown = ObservationDeviceInfo(null, null, ObservationDeviceType.Unknown, null)
        val events = listOf(
            ObservationEvent(1, 0, ObservationEventKind.Connected, 1, deviceInfo = unknown),
            ObservationEvent(2, 1, ObservationEventKind.Disconnected, 1, deviceInfo = unknown.copy(cachedServiceUuids = emptyList())),
        )
        val body = Json.parseToJsonElement(exportBorrowObservation(Instant.EPOCH, ObservationStatus.Stopped, events)).jsonObject
        val infos = body.getValue("events").jsonArray.map { it.jsonObject.getValue("deviceInfo").jsonObject }
        assertEquals(JsonNull, infos[0].getValue("name"))
        assertEquals(JsonNull, infos[0].getValue("bluetoothClass"))
        assertEquals(JsonNull, infos[0].getValue("cachedServiceUuids"))
        assertEquals(0, infos[1].getValue("cachedServiceUuids").jsonArray.size)
    }
}
