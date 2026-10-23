package dev.yikuo.youcommuter.core.domain

import dev.yikuo.youcommuter.core.model.RouteStation
import dev.yikuo.youcommuter.core.model.StationGroup
import dev.yikuo.youcommuter.core.model.StationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StationGroupTest {
    private fun station(index: Int) = RouteStation(StationId("station-$index"), "Station $index")

    @Test
    fun `five unique backups are accepted`() {
        val group = StationGroup(station(0), (1..5).map(::station))

        assertEquals(5, group.backups.size)
    }

    @Test
    fun `more than five backups are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            StationGroup(station(0), (1..6).map(::station))
        }
    }

    @Test
    fun `primary or duplicate backup IDs are rejected`() {
        val primary = station(0)
        assertThrows(IllegalArgumentException::class.java) {
            StationGroup(primary, listOf(primary))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StationGroup(primary, listOf(station(1), station(1)))
        }
    }
}
