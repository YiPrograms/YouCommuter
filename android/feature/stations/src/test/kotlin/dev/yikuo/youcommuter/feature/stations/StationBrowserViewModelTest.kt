package dev.yikuo.youcommuter.feature.stations

import dev.yikuo.youcommuter.core.domain.CoverageRepository
import dev.yikuo.youcommuter.core.domain.StationRepository
import dev.yikuo.youcommuter.core.model.BikeStation
import dev.yikuo.youcommuter.core.model.CoverageResult
import dev.yikuo.youcommuter.core.model.CoverageStatus
import dev.yikuo.youcommuter.core.model.ServiceRegion
import dev.yikuo.youcommuter.core.model.StationId
import dev.yikuo.youcommuter.core.model.StationPage
import dev.yikuo.youcommuter.core.model.StationResult
import dev.yikuo.youcommuter.core.model.StationSystemType
import dev.yikuo.youcommuter.core.model.AvailabilityCounts
import dev.yikuo.youcommuter.core.model.FailureReason
import dev.yikuo.youcommuter.core.model.Freshness
import dev.yikuo.youcommuter.core.model.StationObservation
import dev.yikuo.youcommuter.core.model.StationServiceStatus
import dev.yikuo.youcommuter.core.model.TimestampQuality
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StationBrowserViewModelTest {
    @Test fun `refresh updates cached counts in map and list modes without resetting search`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (showMap in listOf(true, false)) {
                val repository = CachedStationRepository(countedStation(5, 24))
                val model = StationBrowserViewModel(coverageRepository(), repository)
                if (!showMap) model.toggleMap()
                runCurrent()
                model.updateQuery("one")
                advanceTimeBy(300)
                runCurrent()
                repository.next = countedStation(0, 29)

                model.refresh()
                model.refresh() // Ignore a second tap while the first refresh is pending.
                runCurrent()

                assertEquals(1, repository.refreshCalls)
                assertEquals(0, model.state.value.stations.single().observation!!.counts.totalBikes)
                assertEquals(29, model.state.value.stations.single().observation!!.counts.returnDocks)
                assertEquals("one", model.state.value.query)
                assertEquals("00", model.state.value.selectedRegion)
                assertEquals(showMap, model.state.value.showMap)
                assertFalse(model.state.value.loading)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `refresh failure is visible and retry fetches new counts`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = CachedStationRepository(countedStation(5, 24))
            val model = StationBrowserViewModel(coverageRepository(), repository)
            runCurrent()
            repository.refreshResult = StationResult.Failure(FailureReason.Network)
            model.refresh()
            runCurrent()
            assertEquals(FailureReason.Network, model.state.value.failure)
            assertFalse(model.state.value.loading)

            repository.refreshResult = StationResult.Success(Unit)
            repository.next = countedStation(18, 11)
            model.refresh()
            runCurrent()
            assertEquals(null, model.state.value.failure)
            assertEquals(18, model.state.value.stations.single().observation!!.counts.totalBikes)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `detail retry refreshes the source before replacing the selected station`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = CachedStationRepository(countedStation(5, 24))
            val model = StationBrowserViewModel(coverageRepository(), repository)
            runCurrent()
            repository.detailFailure = FailureReason.Network
            model.openStation(model.state.value.stations.single())
            runCurrent()
            assertEquals(FailureReason.Network, model.state.value.detailFailure)
            repository.next = countedStation(18, 11)
            repository.detailFailure = null
            model.retryDetail()
            runCurrent()
            assertEquals(1, repository.refreshCalls)
            assertEquals(18, model.state.value.selectedStation!!.observation!!.counts.totalBikes)
            assertEquals(null, model.state.value.detailFailure)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `search is debounced and pagination appends without duplicates`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val first = station("one")
            val second = station("two")
            val repository = RecordingStationRepository(
                responses = mutableListOf(
                    StationResult.Success(StationPage(listOf(first), "next")),
                    StationResult.Success(StationPage(listOf(first, second), null)),
                    StationResult.Success(StationPage(listOf(second), null)),
                ),
            )
            val model = StationBrowserViewModel(coverageRepository(), repository)
            model.toggleMap()
            runCurrent()
            assertEquals("00", model.state.value.selectedRegion)
            assertEquals(listOf(first), model.state.value.stations)

            model.loadMore()
            runCurrent()
            assertEquals(listOf(first, second), model.state.value.stations)

            model.updateQuery("old")
            model.updateQuery("捷運")
            advanceTimeBy(299)
            assertEquals(2, repository.queries.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("捷運", repository.queries.last())
            assertEquals(listOf(second), model.state.value.stations)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `map mode includes stations beyond the first page`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val first = station("one")
            val second = station("two")
            val repository = RecordingStationRepository(mutableListOf(
                StationResult.Success(StationPage(listOf(first), "next")),
                StationResult.Success(StationPage(listOf(second), null)),
            ))
            val model = StationBrowserViewModel(coverageRepository(), repository)
            runCurrent()
            assertEquals(true, model.state.value.showMap)
            assertEquals(listOf(first, second), model.state.value.stations)
            assertEquals(null, model.state.value.nextCursor)
        } finally { Dispatchers.resetMain() }
    }

    private fun coverageRepository() = object : CoverageRepository {
        override suspend fun getCoverage() = CoverageResult.Success(
            listOf(
                ServiceRegion("NewTaipei", "新北市", CoverageStatus.Available, false),
                ServiceRegion("00", "臺北市", CoverageStatus.Available, true),
            ),
        )
    }

    private fun station(id: String) = BikeStation(
        id = StationId(id),
        name = id,
        address = "",
        regionCode = "00",
        latitude = 25.0,
        longitude = 121.5,
        capacity = null,
        systemType = StationSystemType.YouBike2,
        distanceMeters = null,
        observation = null,
    )

    private fun countedStation(total: Int, docks: Int): BikeStation {
        val at = Instant.parse("2026-10-20T00:00:00Z")
        return station("one").copy(observation = StationObservation(StationId("one"), "$total:$docks", at, at, at, 60,
            TimestampQuality.FeedSnapshot, StationServiceStatus.Operating, AvailabilityCounts(total, total, 0, docks), Freshness.Fresh, "version"))
    }

    @Test fun `refresh retains preview rows and geometry while replacing only observations`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = CachedStationRepository(countedStation(5, 24))
            val model = StationBrowserViewModel(coverageRepository(), repository)
            runCurrent()
            model.openStation(model.state.value.stations.single()); runCurrent()
            val before = model.state.value
            val gate = CompletableDeferred<Unit>()
            repository.refreshGate = gate
            repository.next = countedStation(18, 11).copy(name = "Changed metadata", latitude = 24.0)
            model.refresh(); runCurrent()
            assertEquals(before.stations, model.state.value.stations)
            assertEquals(before.selectedStation, model.state.value.selectedStation)
            assertFalse(model.state.value.loading)
            assertTrue(model.state.value.refreshing)
            gate.complete(Unit); runCurrent()
            val after = model.state.value
            assertEquals(before.stations.single().name, after.stations.single().name)
            assertEquals(before.stations.single().latitude, after.stations.single().latitude, 0.0)
            assertEquals(18, after.stations.single().observation!!.counts.totalBikes)
            assertEquals(18, after.selectedStation!!.observation!!.counts.totalBikes)
            assertEquals(before.nextCursor, after.nextCursor)
            assertEquals(1, repository.searchCalls)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `automatic refresh coalesces pending ticks and has no visible loading transition`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = CachedStationRepository(countedStation(5, 24))
            val model = StationBrowserViewModel(coverageRepository(), repository)
            runCurrent()
            val before = model.state.value.stations
            val gate = CompletableDeferred<Unit>()
            repository.refreshGate = gate
            model.autoRefresh(); runCurrent(); model.autoRefresh()
            assertEquals(1, repository.refreshCalls)
            assertEquals(before, model.state.value.stations)
            assertFalse(model.state.value.loading)
            assertFalse(model.state.value.refreshing)
            gate.complete(Unit); runCurrent()
        } finally { Dispatchers.resetMain() }
    }

    private class CachedStationRepository(private var cached: BikeStation) : StationRepository {
        var next = cached
        var refreshGate: CompletableDeferred<Unit>? = null
        var searchCalls = 0
        var refreshCalls = 0
        var refreshResult: StationResult<Unit> = StationResult.Success(Unit)
        var detailFailure: FailureReason? = null
        override suspend fun location(id: String): StationResult<dev.yikuo.youcommuter.core.model.StationLocation> = error("unused")
        override suspend fun getMany(ids: Set<String>): StationResult<List<BikeStation>> = StationResult.Success(listOf(cached).filter { it.id.value in ids })
        override suspend fun locations(region: String, query: String): StationResult<List<dev.yikuo.youcommuter.core.model.StationLocation>> = error("unused")
        override suspend fun refresh(): StationResult<Unit> {
            refreshCalls++
            refreshGate?.await()
            if (refreshResult is StationResult.Success) cached = next
            return refreshResult
        }
        override suspend fun search(region: String, query: String, cursor: String?, limit: Int): StationResult<StationPage> {
            searchCalls++
            return StationResult.Success(StationPage(listOf(cached), null))
        }
        override suspend fun get(id: String): StationResult<BikeStation> = detailFailure?.let { StationResult.Failure(it) }
            ?: StationResult.Success(cached)
    }

    private class RecordingStationRepository(
        private val responses: MutableList<StationResult<StationPage>>,
    ) : StationRepository {
        val queries = mutableListOf<String>()

        override suspend fun location(id: String): StationResult<dev.yikuo.youcommuter.core.model.StationLocation> = error("unused")
        override suspend fun getMany(ids: Set<String>): StationResult<List<BikeStation>> = error("unused")
        override suspend fun locations(region: String, query: String): StationResult<List<dev.yikuo.youcommuter.core.model.StationLocation>> = error("unused")
        override suspend fun refresh(): StationResult<Unit> = error("unused")

        override suspend fun search(region: String, query: String, cursor: String?, limit: Int): StationResult<StationPage> {
            queries += query
            return responses.removeFirst()
        }

        override suspend fun get(id: String): StationResult<BikeStation> = error("unused")
    }
}
