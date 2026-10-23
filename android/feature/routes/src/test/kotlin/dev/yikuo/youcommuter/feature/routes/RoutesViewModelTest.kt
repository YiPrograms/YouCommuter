package dev.yikuo.youcommuter.feature.routes

import dev.yikuo.youcommuter.core.domain.CoverageRepository
import dev.yikuo.youcommuter.core.domain.RouteRepository
import dev.yikuo.youcommuter.core.domain.StationRepository
import dev.yikuo.youcommuter.core.model.BikeStation
import dev.yikuo.youcommuter.core.model.CommuteRoute
import dev.yikuo.youcommuter.core.model.CoverageResult
import dev.yikuo.youcommuter.core.model.FailureReason
import dev.yikuo.youcommuter.core.model.RouteStation
import dev.yikuo.youcommuter.core.model.StationGroup
import dev.yikuo.youcommuter.core.model.StationId
import dev.yikuo.youcommuter.core.model.StationResult
import dev.yikuo.youcommuter.core.model.ServiceRegion
import dev.yikuo.youcommuter.core.model.CoverageStatus
import dev.yikuo.youcommuter.core.model.BikeKind
import dev.yikuo.youcommuter.core.model.StationLocation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutesViewModelTest {
    @Test fun `picker keeps primary visible but prevents selecting it as a backup`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            fun station(id: String) = StationLocation(StationId(id), id, "", "00", 25.0, 121.5)
            val first = station("first")
            val second = station("second")
            val model = RoutesViewModel(
                object : CoverageRepository {
                    override suspend fun getCoverage() = CoverageResult.Success(
                        listOf(ServiceRegion("00", "台北市", CoverageStatus.Available, true)))
                },
                object : StationRepository {
                    override suspend fun location(id: String) = StationResult.Success(listOf(first, second).first { it.id.value == id })
                    override suspend fun getMany(ids: Set<String>): StationResult<List<BikeStation>> = error("unused")
                    override suspend fun locations(region: String, query: String) = StationResult.Success(listOf(first, second))
                    override suspend fun refresh(): StationResult<Unit> = error("unused")
                    override suspend fun search(region: String, query: String, cursor: String?, limit: Int) =
                        error("Picker must not load inventory pages")
                    override suspend fun get(id: String): StationResult<BikeStation> = error("unused")
                },
                object : RouteRepository {
                    override fun observeRoutes() = flowOf(emptyList<CommuteRoute>())
                    override suspend fun save(route: CommuteRoute) = Unit
                    override suspend fun delete(id: UUID) = Unit
                },
            )
            runCurrent()
            model.addRoute()
            model.openPicker(StationTarget.BorrowPrimary)
            runCurrent()
            assertTrue(model.state.value.picker!!.showMap)
            assertEquals(listOf(first, second), model.state.value.picker!!.stations)
            model.selectStation(first)
            model.openPicker(StationTarget.BorrowBackup)
            runCurrent()
            assertEquals(listOf(first, second), model.state.value.picker!!.stations)
            assertEquals(first, model.state.value.picker!!.primary)
            model.selectStation(first)
            assertNotNull(model.state.value.picker)
            assertTrue(model.state.value.editor!!.borrowBackups.isEmpty())
            model.selectStation(second)
            assertNull(model.state.value.picker)
            assertEquals(second.id, model.state.value.editor!!.borrowBackups.single().id)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `unchanged draft closes even after validation error`() = withModel { model ->
        model.addRoute()
        model.save()
        assertEquals(RouteEditorIssue.MissingName, model.state.value.editor?.issue)
        model.closeEditor()
        assertNull(model.state.value.editor)
        assertFalse(model.state.value.confirmDiscard)
    }

    @Test fun `back protects modified draft and keeping it preserves all values`() = withModel { model ->
        model.addRoute()
        model.updateName("Morning")
        model.updateEstimatedReturnMinutes("25")
        model.setLowBikeThreshold(5)
        val draft = model.state.value.editor
        model.closeEditor()
        assertTrue(model.state.value.confirmDiscard)
        model.keepEditing()
        assertEquals(draft, model.state.value.editor)
        assertFalse(model.state.value.confirmDiscard)
        model.closeEditor()
        model.discardEditor()
        assertNull(model.state.value.editor)
    }

    @Test fun `reverting changes does not require discard`() = withModel { model ->
        model.addRoute()
        model.updateName("Morning")
        model.updateName("")
        model.closeEditor()
        assertNull(model.state.value.editor)
        assertFalse(model.state.value.confirmDiscard)
    }

    @Test fun `leaving station picker preserves route draft`() = withModel { model ->
        model.addRoute()
        model.updateName("Morning")
        val draft = model.state.value.editor
        model.openPicker(StationTarget.ReturnBackup)
        model.updateStationQuery("station")
        model.closePicker()
        assertNull(model.state.value.picker)
        assertEquals(draft, model.state.value.editor)
    }

    @Test fun `late loading result after back cannot reopen picker or alter the draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val response = CompletableDeferred<StationResult<List<StationLocation>>>()
            val model = model(locations = { withContext(NonCancellable) { response.await() } })
            val saved = route()
            model.editRoute(saved)
            val draft = model.state.value.editor
            model.openPicker(StationTarget.BorrowBackup)
            runCurrent()
            assertTrue(model.state.value.picker!!.loading)
            model.closePicker()
            model.closeEditor() // A second back closes the unchanged editor.
            response.complete(StationResult.Success(listOf(location("late"))))
            runCurrent()
            assertNull(model.state.value.editor)
            assertNull(model.state.value.picker)
            model.editRoute(saved)
            assertEquals(draft, model.state.value.editor)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `cancelled backup search cannot replace a newly opened picker`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val oldResponse = CompletableDeferred<StationResult<List<StationLocation>>>()
            var calls = 0
            val current = location("current")
            val model = model(locations = {
                if (calls++ == 0) withContext(NonCancellable) { oldResponse.await() }
                else StationResult.Success(listOf(current))
            })
            model.editRoute(route())
            model.openPicker(StationTarget.BorrowBackup)
            runCurrent()
            model.closePicker()
            model.openPicker(StationTarget.ReturnBackup)
            runCurrent()
            oldResponse.complete(StationResult.Success(listOf(location("old"))))
            runCurrent()
            assertEquals(StationTarget.ReturnBackup, model.state.value.picker!!.target)
            assertEquals(listOf(current), model.state.value.picker!!.stations)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `failed save retains editable draft and back protection`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val model = model(failSave = true)
            model.editRoute(route())
            model.updateName("Changed")
            model.save()
            model.closeEditor() // A pending save cannot be dismissed.
            assertNotNull(model.state.value.editor)
            assertFalse(model.state.value.confirmDiscard)
            runCurrent()
            assertEquals(RouteEditorIssue.Storage, model.state.value.editor?.issue)
            assertEquals("Changed", model.state.value.editor?.name)
            assertFalse(model.state.value.editor!!.saving)
            model.closeEditor()
            assertTrue(model.state.value.confirmDiscard)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `duplicating and reversing preserves full groups and saves with a new identity`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = route().copy(
                borrow = StationGroup(RouteStation(StationId("a"), "A"), listOf(RouteStation(StationId("b"), "B"))),
                returns = StationGroup(RouteStation(StationId("c"), "C"), listOf(RouteStation(StationId("d"), "D"))),
                bikeDisplay = BikeKind.All, bikeThreshold = BikeKind.Electric, estimatedReturnMinutes = 25)
            var saved: CommuteRoute? = null
            val model = model(onSaved = { saved = it })
            model.duplicateRoute(source, "Work copy")
            assertNull(model.state.value.editor!!.id)
            model.swapStations()
            assertEquals(source.returns.primary, model.state.value.editor!!.borrow)
            assertEquals(source.returns.backups, model.state.value.editor!!.borrowBackups)
            assertEquals(source.borrow.backups, model.state.value.editor!!.returnBackups)
            model.save(); runCurrent()
            assertNotEquals(source.id, saved!!.id)
            assertEquals(1L, saved!!.revision)
            assertEquals(source.returns, saved!!.borrow)
            assertEquals(source.borrow, saved!!.returns)
            assertEquals(BikeKind.Electric, saved!!.thresholdBikeKind)
            assertEquals(25, saved!!.estimatedReturnMinutes)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `single displayed bike kind locks the threshold until both kinds are displayed`() = withModel { model ->
        model.addRoute()
        model.setBikeDisplay(BikeKind.Electric)
        model.setBikeThreshold(BikeKind.Regular)
        assertEquals(BikeKind.Electric, model.state.value.editor!!.bikeThreshold)
        model.setBikeDisplay(BikeKind.All)
        model.setBikeThreshold(BikeKind.Regular)
        assertEquals(BikeKind.Regular, model.state.value.editor!!.bikeThreshold)
    }

    @Test fun `backup picker starts in the primary region and retains reference after changing region`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val primary = location("borrow").copy(regionCode = "10")
            val model = model(locations = { StationResult.Success(emptyList()) }, primaryLocation = { StationResult.Success(primary) })
            model.editRoute(route()); model.openPicker(StationTarget.BorrowBackup); runCurrent()
            assertEquals("10", model.state.value.picker!!.selectedRegion)
            assertEquals(primary, model.state.value.picker!!.primary)
            model.selectRegion("00"); runCurrent()
            assertEquals("00", model.state.value.picker!!.selectedRegion)
            assertEquals(primary, model.state.value.picker!!.primary)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `picker camera follows completed search results instead of the loading query`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val gate = CompletableDeferred<StationResult<List<StationLocation>>>()
            var calls = 0
            val model = model(locations = {
                if (calls++ == 0) StationResult.Success(listOf(location("first"))) else gate.await()
            })
            model.addRoute(); model.openPicker(StationTarget.BorrowPrimary); runCurrent()
            val initialKey = model.state.value.picker!!.mapCameraKey
            model.updateStationQuery("132"); advanceTimeBy(300); runCurrent()
            assertEquals(initialKey, model.state.value.picker!!.mapCameraKey)
            assertEquals("first", model.state.value.picker!!.stations.single().name)
            gate.complete(StationResult.Success(listOf(location("new")))); runCurrent()
            assertNotEquals(initialKey, model.state.value.picker!!.mapCameraKey)
            assertEquals("new", model.state.value.picker!!.stations.single().name)
        } finally { Dispatchers.resetMain() }
    }

    private fun withModel(block: (RoutesViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block(model()) } finally { Dispatchers.resetMain() }
    }

    private fun model(
        failSave: Boolean = false,
        locations: suspend () -> StationResult<List<StationLocation>> = { StationResult.Failure(FailureReason.Network) },
        primaryLocation: (suspend (String) -> StationResult<StationLocation>)? = null,
        onSaved: (CommuteRoute) -> Unit = {},
    ) = RoutesViewModel(
        object : CoverageRepository {
            override suspend fun getCoverage() = CoverageResult.Success(emptyList())
        },
        object : StationRepository {
            override suspend fun location(id: String) = primaryLocation?.invoke(id) ?: StationResult.Success(this@RoutesViewModelTest.location(id))
            override suspend fun getMany(ids: Set<String>): StationResult<List<BikeStation>> = error("unused")
            override suspend fun locations(region: String, query: String) = locations()
            override suspend fun refresh(): StationResult<Unit> = error("unused")
            override suspend fun search(region: String, query: String, cursor: String?, limit: Int) =
                StationResult.Failure(FailureReason.Network)
            override suspend fun get(id: String): StationResult<BikeStation> = StationResult.Failure(FailureReason.Network)
        },
        object : RouteRepository {
            override fun observeRoutes() = flowOf(emptyList<CommuteRoute>())
            override suspend fun save(route: CommuteRoute) { if (failSave) error("Storage unavailable"); onSaved(route) }
            override suspend fun delete(id: UUID) = Unit
        },
    )

    private fun location(id: String) = StationLocation(StationId(id), id, "Address", "00", 25.0, 121.5)

    private fun route() = CommuteRoute(
        UUID.randomUUID(), "Work",
        StationGroup(RouteStation(StationId("borrow"), "Borrow")),
        StationGroup(RouteStation(StationId("return"), "Return")),
        LocalTime.of(8, 30), emptySet(), revision = 1,
    )
}
