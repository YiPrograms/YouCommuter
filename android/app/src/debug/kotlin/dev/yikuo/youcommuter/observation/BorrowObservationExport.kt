package dev.yikuo.youcommuter.observation

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private val exportJson = Json { prettyPrint = true }

internal fun exportBorrowObservation(startedAt: Instant, status: ObservationStatus, events: List<ObservationEvent>): String {
    val body = buildJsonObject {
        put("schemaVersion", 2)
        put("startedAt", startedAt.toString())
        put("status", status.name)
        putJsonArray("events") {
            for (event in events) addJsonObject {
                put("number", event.number)
                put("elapsedMillis", event.elapsedMillis)
                put("kind", event.kind.name)
                put("device", event.device?.let(::JsonPrimitive) ?: JsonNull)
                put("transport", event.transport.name)
                put("deviceInfo", event.deviceInfo?.toJson() ?: JsonNull)
            }
        }
    }
    return exportJson.encodeToString(JsonObject.serializer(), body)
}

private fun ObservationDeviceInfo.toJson(): JsonElement = buildJsonObject {
    put("name", name?.let(::JsonPrimitive) ?: JsonNull)
    put("cachedServiceUuids", cachedServiceUuids?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
    put("type", type.name)
    put("bluetoothClass", bluetoothClass?.let(::JsonPrimitive) ?: JsonNull)
}
