package dev.yikuo.youcommuter.observation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationDeviceInfoTest {
    @Test
    fun `unavailable cache fields do not hide other available device information`() {
        val info = readCachedDeviceInfo(
            name = { throw IllegalStateException("Bluetooth service unavailable") },
            serviceUuids = { listOf("uuid-b", "uuid-a", "uuid-b") },
            type = { ObservationDeviceType.LowEnergy },
            bluetoothClass = { throw IllegalStateException("No class") },
        )
        assertNull(info.name)
        assertNull(info.bluetoothClass)
        assertEquals(listOf("uuid-a", "uuid-b"), info.cachedServiceUuids)
        assertEquals(ObservationDeviceType.LowEnergy, info.type)
    }

    @Test
    fun `missing UUID cache remains different from a known empty cache`() {
        fun read(uuids: List<String>?) = readCachedDeviceInfo({ null }, { uuids },
            { throw IllegalStateException("No type") }, { null })
        assertNull(read(null).cachedServiceUuids)
        assertEquals(emptyList<String>(), read(emptyList()).cachedServiceUuids)
        assertEquals(ObservationDeviceType.Unknown, read(null).type)
    }

    @Test(expected = SecurityException::class)
    fun `permission loss propagates instead of looking like an unknown device`() {
        readCachedDeviceInfo({ null }, { throw SecurityException("Revoked") }, { ObservationDeviceType.LowEnergy }, { null })
    }

    @Test
    fun `device metadata is bounded and control characters do not enter the UI`() {
        val info = readCachedDeviceInfo({ "\n" + "x".repeat(500) + "\u0000" },
            { (1..100).map { it.toString() } }, { ObservationDeviceType.Dual }, { 123 })
        assertEquals(248, info.name!!.length)
        assertTrue(info.name.none { it.isISOControl() })
        assertEquals(64, info.cachedServiceUuids!!.size)
    }
}
