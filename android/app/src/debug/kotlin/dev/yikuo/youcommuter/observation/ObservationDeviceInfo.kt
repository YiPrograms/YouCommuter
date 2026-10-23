package dev.yikuo.youcommuter.observation

internal enum class ObservationDeviceType { Unknown, Classic, LowEnergy, Dual }

/** A cache snapshot, not a discovered service list or a verified bike identity. */
internal data class ObservationDeviceInfo(
    val name: String?,
    val cachedServiceUuids: List<String>?,
    val type: ObservationDeviceType,
    val bluetoothClass: Int?,
)

/** Optional cache failures must not hide a link event or other available fields. */
internal fun readCachedDeviceInfo(
    name: () -> String?,
    serviceUuids: () -> List<String>?,
    type: () -> ObservationDeviceType,
    bluetoothClass: () -> Int?,
): ObservationDeviceInfo = ObservationDeviceInfo(
    name = cachedValue(name)?.filterNot { it.isISOControl() }?.trim()?.take(248)?.takeIf { it.isNotEmpty() },
    cachedServiceUuids = cachedValue(serviceUuids)?.distinct()?.sorted()?.take(64),
    type = cachedValue(type) ?: ObservationDeviceType.Unknown,
    bluetoothClass = cachedValue(bluetoothClass),
)

private fun <T> cachedValue(read: () -> T): T? = try {
    read()
} catch (error: SecurityException) {
    throw error // Permission revocation still ends observation.
} catch (_: RuntimeException) {
    null
}

/** The address is an in-memory correlation key and is never included in exports. */
internal data class BluetoothLinkObservation(
    val kind: ObservationEventKind,
    val address: String,
    val transport: ObservationTransport,
    val receivedElapsedRealtimeMillis: Long,
    val deviceInfo: ObservationDeviceInfo,
)
