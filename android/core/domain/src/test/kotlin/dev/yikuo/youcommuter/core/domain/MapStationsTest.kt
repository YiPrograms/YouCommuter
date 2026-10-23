package dev.yikuo.youcommuter.core.domain

import dev.yikuo.youcommuter.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MapStationsTest {
    @Test fun `maps receive every page and deduplicate boundary records`() = runBlocking {
        val requests = mutableListOf<String?>()
        val repository = repository { cursor ->
            requests += cursor
            if (cursor == null) StationResult.Success(StationPage(listOf(station("one")), "next"))
            else StationResult.Success(StationPage(listOf(station("one"), station("two")), null))
        }
        val result = repository.mapStations("00", "  park  ") as StationResult.Success
        assertEquals(listOf("one", "two"), result.value.map { it.id.value })
        assertEquals(listOf(null, "next"), requests)
    }

    @Test fun `repeated cursor is rejected instead of looping forever`() = runBlocking {
        val repository = repository { StationResult.Success(StationPage(listOf(station("one")), "repeat")) }
        assertEquals(StationResult.Failure(FailureReason.InvalidResponse), repository.mapStations("00", "park"))
    }

    @Test fun `a failed later page does not masquerade as a complete map`() = runBlocking {
        val repository = repository { cursor ->
            if (cursor == null) StationResult.Success(StationPage(listOf(station("one")), "next"))
            else StationResult.Failure(FailureReason.Network)
        }
        assertEquals(StationResult.Failure(FailureReason.Network), repository.mapStations("00", "park"))
    }

    private fun repository(page: suspend (String?) -> StationResult<StationPage>) = object : StationRepository {
        override suspend fun location(id: String): StationResult<dev.yikuo.youcommuter.core.model.StationLocation> = error("unused")
        override suspend fun getMany(ids: Set<String>): StationResult<List<BikeStation>> = error("unused")
        override suspend fun locations(region: String, query: String): StationResult<List<dev.yikuo.youcommuter.core.model.StationLocation>> = error("unused")
        override suspend fun refresh(): StationResult<Unit> = error("unused")
        override suspend fun search(region: String, query: String, cursor: String?, limit: Int): StationResult<StationPage> {
            assertEquals("00", region)
            assertEquals("park", query)
            assertEquals(200, limit)
            return page(cursor)
        }
        override suspend fun get(id: String): StationResult<BikeStation> = error("unused")
    }

    private fun station(id: String) = BikeStation(StationId(id), id, "", "00", 25.0, 121.5,
        null, StationSystemType.YouBike2, null, null)
}
