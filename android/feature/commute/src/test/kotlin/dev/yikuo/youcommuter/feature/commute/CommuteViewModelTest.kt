package dev.yikuo.youcommuter.feature.commute

import androidx.lifecycle.ViewModelStore
import dev.yikuo.youcommuter.core.domain.usableCounts
import dev.yikuo.youcommuter.core.domain.CommuteCommand
import dev.yikuo.youcommuter.core.domain.CommuteReducer
import dev.yikuo.youcommuter.core.domain.CommuteSessionRepository
import dev.yikuo.youcommuter.core.domain.RouteRepository
import dev.yikuo.youcommuter.core.domain.StationRepository
import dev.yikuo.youcommuter.core.model.*
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommuteViewModelTest {
    private val borrow = RouteStation(StationId("borrow"), "Borrow")
    private val returns = RouteStation(StationId("return"), "Return")
    private val backup = RouteStation(StationId("backup"), "Backup")
    private val at = Instant.parse("2026-10-20T00:00:00Z")
    private val route = CommuteRoute(
        UUID.randomUUID(), "Work", StationGroup(borrow, listOf(backup)), StationGroup(returns, listOf(backup)),
        LocalTime.of(8, 30), emptySet(), revision = 1,
    )
    private val store = ViewModelStore()

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun `one refresh replaces cached counts for both selected stations and map backups`() = runTest {
        var updated = false
        var refreshes = 0
        val model = model(Sessions(initial()), refresh = {
            refreshes++
            updated = true
            StationResult.Success(Unit)
        }) { id -> StationResult.Success(if (updated) updatedStation(id) else station(id)) }
        runCurrent()
        assertEquals(12, model.state.value.borrowAvailability.station!!.observation!!.counts.totalBikes)

        model.refreshAvailability()
        model.refreshAvailability()
        runCurrent()

        assertEquals(1, refreshes)
        assertFalse(model.state.value.refreshingAvailability)
        assertEquals(0, model.state.value.borrowAvailability.station!!.observation!!.counts.totalBikes)
        assertEquals(36, model.state.value.returnAvailability.station!!.observation!!.counts.returnDocks)
        assertEquals(36, model.state.value.mapStations.single { it.id == backup.id }.observation!!.counts.returnDocks)
    }

    @Test fun `refresh errors are visible instead of silently reusing counts and retry recovers`() = runTest {
        var result: StationResult<Unit> = StationResult.Failure(FailureReason.Network)
        val model = model(Sessions(initial()), refresh = { result }) { id -> StationResult.Success(updatedStation(id)) }
        runCurrent()
        model.refreshAvailability()
        runCurrent()
        assertFalse(model.state.value.refreshingAvailability)
        assertFalse(model.state.value.borrowAvailability.loading)
        assertFalse(model.state.value.returnAvailability.loading)
        assertEquals(FailureReason.Network, model.state.value.borrowAvailability.failure)
        assertEquals(FailureReason.Network, model.state.value.returnAvailability.failure)
        assertTrue(model.state.value.mapIncomplete)

        result = StationResult.Success(Unit)
        model.refreshAvailability()
        runCurrent()
        assertNull(model.state.value.borrowAvailability.failure)
        assertNull(model.state.value.returnAvailability.failure)
        assertFalse(model.state.value.mapIncomplete)
        assertEquals(36, model.state.value.returnAvailability.station!!.observation!!.counts.returnDocks)
    }

    @Test fun `pending refresh reloads the latest destination after a selection change`() = runTest {
        val refreshed = CompletableDeferred<StationResult<Unit>>()
        var updated = false
        val model = model(Sessions(initial()), refresh = { refreshed.await() }) { id ->
            StationResult.Success(if (updated) updatedStation(id) else station(id))
        }
        runCurrent()
        model.refreshAvailability()
        runCurrent()
        model.selectReturnStation(backup)
        runCurrent()
        updated = true
        refreshed.complete(StationResult.Success(Unit))
        runCurrent()
        assertEquals(backup, model.state.value.session!!.selectedReturn)
        assertEquals(backup.id, model.state.value.returnAvailability.station!!.id)
        assertEquals(36, model.state.value.returnAvailability.station!!.observation!!.counts.returnDocks)
    }

    @Test fun `a completed session cancels refresh and its late error cannot affect a new commute`() = runTest {
        val refreshed = CompletableDeferred<StationResult<Unit>>()
        val model = model(Sessions(initial()), refresh = { withContext(NonCancellable) { refreshed.await() } }) { id ->
            StationResult.Success(station(id))
        }
        runCurrent()
        model.refreshAvailability()
        runCurrent()
        assertTrue(model.state.value.refreshingAvailability)
        model.confirmBorrowed()
        runCurrent()
        model.confirmReturned()
        runCurrent()
        assertFalse(model.state.value.refreshingAvailability)
        assertEquals(StationAvailability(), model.state.value.returnAvailability)
        model.start(route)
        runCurrent()
        refreshed.complete(StationResult.Failure(FailureReason.Network))
        runCurrent()
        assertEquals(CommutePhase.WalkingToBorrow, model.state.value.session!!.phase)
        assertNull(model.state.value.borrowAvailability.failure)
        assertNull(model.state.value.returnAvailability.failure)
        assertEquals(7, model.state.value.returnAvailability.station!!.observation!!.counts.returnDocks)
    }

    @Test fun `preparing and walking load both stations with distinct counts`() = runTest {
        for (phase in listOf(CommutePhase.Preparing, CommutePhase.WalkingToBorrow)) {
            val sessions = Sessions(initial().copy(phase = phase))
            val model = model(sessions) { id -> StationResult.Success(station(id)) }
            runCurrent()

            assertEquals(10, model.state.value.borrowAvailability.station?.observation?.counts?.regularBikes)
            assertEquals(2, model.state.value.borrowAvailability.station?.observation?.counts?.electricBikes)
            assertEquals(7, model.state.value.returnAvailability.station?.observation?.counts?.returnDocks)
            assertFalse(model.state.value.borrowAvailability.loading)
            assertFalse(model.state.value.returnAvailability.loading)
            assertEquals(setOf(borrow.id, returns.id, backup.id), model.state.value.mapStations.map { it.id }.toSet())
        }
    }

    @Test fun `a missing destination does not hide available borrow inventory`() = runTest {
        val model = model(Sessions(initial()), batch = {
            StationResult.Success(listOf(station(borrow.id.value), station(backup.id.value)))
        }) { id -> StationResult.Success(station(id)) }
        runCurrent()
        assertEquals(borrow.id, model.state.value.borrowAvailability.station?.id)
        assertFalse(model.state.value.borrowAvailability.loading)
        assertNull(model.state.value.borrowAvailability.failure)
        assertEquals(FailureReason.InvalidResponse, model.state.value.returnAvailability.failure)
        assertFalse(model.state.value.returnAvailability.loading)
    }

    @Test fun `destination can change before borrowing and old response cannot overwrite backup`() = runTest {
        val oldDestination = CompletableDeferred<StationResult<BikeStation>>()
        val model = model(Sessions(initial())) { id ->
            if (id == returns.id.value) withContext(NonCancellable) { oldDestination.await() }
            else StationResult.Success(station(id))
        }
        runCurrent()
        val borrowAvailability = model.state.value.borrowAvailability
        model.selectReturnStation(backup)
        runCurrent()
        assertEquals(backup, model.state.value.session?.selectedReturn)
        assertTrue(model.state.value.returnAvailability.loading)
        assertEquals(borrowAvailability, model.state.value.borrowAvailability)

        oldDestination.complete(StationResult.Success(station(returns.id.value)))
        runCurrent()
        assertEquals(backup.id, model.state.value.returnAvailability.station?.id)
        assertNull(model.state.value.issue)
    }

    @Test fun `changing borrow station preserves destination and refresh reloads both`() = runTest {
        val calls = mutableListOf<String>()
        val model = model(Sessions(initial())) { id ->
            calls += id
            StationResult.Success(station(id))
        }
        runCurrent()
        val returnAvailability = model.state.value.returnAvailability
        val returnRequests = calls.count { it == returns.id.value }
        model.selectBorrowStation(backup)
        runCurrent()
        assertEquals(backup.id, model.state.value.borrowAvailability.station?.id)
        assertEquals(returnAvailability, model.state.value.returnAvailability)
        assertEquals(returnRequests, calls.count { it == returns.id.value })

        val borrowRequests = calls.count { it == backup.id.value }
        model.refreshAvailability()
        runCurrent()
        assertEquals(backup.id, model.state.value.borrowAvailability.station?.id)
        assertEquals(returns.id, model.state.value.returnAvailability.station?.id)
        assertTrue(calls.count { it == backup.id.value } > borrowRequests)
        assertTrue(calls.count { it == returns.id.value } > returnRequests)
    }

    @Test fun `borrowing removes borrow inventory and completion clears destination`() = runTest {
        val calls = mutableListOf<String>()
        val model = model(Sessions(initial())) { id ->
            calls += id
            StationResult.Success(station(id))
        }
        runCurrent()
        val borrowRequests = calls.count { it == borrow.id.value }
        val returnAvailability = model.state.value.returnAvailability
        model.confirmBorrowed()
        runCurrent()
        assertEquals(CommutePhase.Riding, model.state.value.session?.phase)
        assertEquals(StationAvailability(), model.state.value.borrowAvailability)
        assertEquals(returnAvailability, model.state.value.returnAvailability)

        model.refreshAvailability()
        runCurrent()
        assertEquals(borrowRequests, calls.count { it == borrow.id.value })
        assertFalse(model.state.value.mapStations.any { it.id == borrow.id })
        model.confirmReturned()
        runCurrent()
        assertEquals(CommutePhase.Completed, model.state.value.session?.phase)
        assertEquals(StationAvailability(), model.state.value.returnAvailability)
        assertTrue(model.state.value.mapStations.isEmpty())
    }

    @Test fun `automatic refresh retains all rows and does not show a loading transition`() = runTest {
        val gate = CompletableDeferred<StationResult<Unit>>()
        var refreshes = 0
        val model = model(Sessions(initial()), refresh = { refreshes++; gate.await() }) { id -> StationResult.Success(station(id)) }
        runCurrent()
        val before = model.state.value.mapStations
        model.autoRefresh(); runCurrent(); model.autoRefresh()
        assertEquals(before, model.state.value.mapStations)
        assertFalse(model.state.value.mapLoading)
        assertFalse(model.state.value.refreshingAvailability)
        assertEquals(1, refreshes)
        model.selectBorrowStation(backup); runCurrent()
        assertEquals(backup.id, model.state.value.borrowAvailability.station!!.id)
        assertFalse(model.state.value.borrowAvailability.loading)
        gate.complete(StationResult.Success(Unit)); runCurrent()
        assertEquals(before, model.state.value.mapStations)
    }

    @Test fun `a missing destination stays unavailable after the borrowing phase changes`() = runTest {
        var missing = false
        val model = model(Sessions(initial()), batch = { ids ->
            StationResult.Success(ids.filterNot { missing && it == returns.id.value }.map(::station))
        }) { id -> StationResult.Success(station(id)) }
        runCurrent()
        missing = true
        model.refreshAvailability(); runCurrent()
        assertEquals(FailureReason.InvalidResponse, model.state.value.returnAvailability.failure)
        model.confirmBorrowed(); runCurrent()
        assertEquals(FailureReason.InvalidResponse, model.state.value.returnAvailability.failure)
        assertNull(model.state.value.stationForCounts(returns.id))
    }

    @Test fun `failed background refresh retains fresh counts but ages them out during an outage`() = runTest {
        val clock = object : Clock() {
            var now = at
            override fun instant() = now
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
        }
        val model = model(Sessions(initial()), refresh = { StationResult.Failure(FailureReason.Network) }, clock = clock) { id ->
            StationResult.Success(station(id))
        }
        runCurrent()
        model.autoRefresh(); runCurrent()
        assertEquals(12, model.state.value.stationForCounts(borrow.id)!!.usableCounts!!.totalBikes)
        assertEquals(FailureReason.Network, model.state.value.inventoryFailure)
        clock.now = at.plusSeconds(11 * 60)
        model.autoRefresh(); runCurrent()
        assertNull(model.state.value.stationForCounts(borrow.id)!!.usableCounts)
    }

    private fun initial() = CommuteReducer.start(route, UUID.randomUUID(), at)

    private fun model(sessions: Sessions, refresh: suspend () -> StationResult<Unit> = { StationResult.Success(Unit) },
        batch: (suspend (Set<String>) -> StationResult<List<BikeStation>>)? = null,
        clock: Clock = Clock.systemUTC(),
        get: suspend (String) -> StationResult<BikeStation>): CommuteViewModel {
        val routes = object : RouteRepository {
            override fun observeRoutes() = flowOf(listOf(route))
            override suspend fun save(route: CommuteRoute) = Unit
            override suspend fun delete(id: UUID) = Unit
        }
        val stations = object : StationRepository {
            override suspend fun locations(region: String, query: String): StationResult<List<StationLocation>> = error("unused")
            override suspend fun location(id: String): StationResult<StationLocation> = error("unused")
            override suspend fun getMany(ids: Set<String>): StationResult<List<BikeStation>> {
                if (batch != null) return batch(ids)
                val stations = mutableListOf<BikeStation>()
                for (id in ids) when (val result = get.invoke(id)) {
                    is StationResult.Success -> stations += result.value
                    is StationResult.Failure -> return result
                }
                return StationResult.Success(stations)
            }
            override suspend fun refresh() = refresh.invoke()
            override suspend fun search(region: String, query: String, cursor: String?, limit: Int) =
                StationResult.Success(StationPage(emptyList(), null))
            override suspend fun get(id: String) = get.invoke(id)
        }
        return CommuteViewModel(routes, sessions, stations, clock).also { store.put(UUID.randomUUID().toString(), it) }
    }

    private fun station(id: String) = BikeStation(
        StationId(id), id, "", "00", 25.0, 121.5, 40, StationSystemType.YouBike2, null,
        StationObservation(StationId(id), id, at, at, at, 60, TimestampQuality.FeedSnapshot,
            StationServiceStatus.Operating,
            if (id == borrow.id.value) AvailabilityCounts(12, 10, 2, 28) else AvailabilityCounts(33, 30, 3, 7),
            Freshness.Fresh, "version"),
    )

    private fun updatedStation(id: String): BikeStation = station(id).let { station ->
        station.copy(observation = station.observation!!.copy(
            counts = if (id == borrow.id.value) AvailabilityCounts(0, 0, 0, 40) else AvailabilityCounts(4, 3, 1, 36),
        ))
    }

    private class Sessions(initial: CommuteSession) : CommuteSessionRepository {
        private val current = MutableStateFlow<CommuteSession?>(initial)
        override fun observeSession() = current
        override suspend fun start(route: CommuteRoute, at: Instant) =
            CommuteReducer.start(route, UUID.randomUUID(), at).also { current.value = it }
        override suspend fun dispatch(sessionId: UUID, command: CommuteCommand) =
            CommuteReducer.reduce(checkNotNull(current.value), sessionId, command).also { current.value = it }
        override suspend fun clearTerminal() { current.value = null }
    }
}
