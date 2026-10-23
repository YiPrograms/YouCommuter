package dev.yikuo.youcommuter.feature.commute

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.yikuo.youcommuter.core.domain.CommuteCommand
import dev.yikuo.youcommuter.core.domain.CommuteSessionRepository
import dev.yikuo.youcommuter.core.domain.RouteRepository
import dev.yikuo.youcommuter.core.domain.StationRepository
import dev.yikuo.youcommuter.core.domain.AvailabilityFreshness
import java.time.Duration
import dev.yikuo.youcommuter.core.domain.all
import dev.yikuo.youcommuter.core.model.BikeStation
import dev.yikuo.youcommuter.core.model.CommutePhase
import dev.yikuo.youcommuter.core.model.CommuteRoute
import dev.yikuo.youcommuter.core.model.CommuteSession
import dev.yikuo.youcommuter.core.model.FailureReason
import dev.yikuo.youcommuter.core.model.RouteStation
import dev.yikuo.youcommuter.core.model.StationId
import dev.yikuo.youcommuter.core.model.StationResult
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CommuteIssue { InvalidAction, Storage }

data class StationAvailability(
    val station: BikeStation? = null,
    val loading: Boolean = false,
    val failure: FailureReason? = null,
)

data class CommuteUiState(
    val routes: List<CommuteRoute> = emptyList(),
    val session: CommuteSession? = null,
    val mapStations: List<BikeStation> = emptyList(),
    val mapLoading: Boolean = false,
    val mapIncomplete: Boolean = false,
    val inventoryFailure: FailureReason? = null,
    val unavailableStationIds: Set<StationId> = emptySet(),
    val refreshingAvailability: Boolean = false,
    val busy: Boolean = false,
    val issue: CommuteIssue? = null,
) {
    fun stationForCounts(id: StationId): BikeStation? = mapStations.firstOrNull {
        it.id == id && id !in unavailableStationIds
    }
    val borrowAvailability: StationAvailability get() = availability(session?.selectedBorrow, session?.isBorrowing == true)
    val returnAvailability: StationAvailability get() = availability(session?.selectedReturn, session?.hasInventory == true)
    private fun availability(ref: RouteStation?, needed: Boolean): StationAvailability {
        if (!needed || ref == null) return StationAvailability()
        val station = stationForCounts(ref.id)
        return StationAvailability(station, mapLoading && station == null,
            inventoryFailure ?: FailureReason.InvalidResponse.takeIf { ref.id in unavailableStationIds })
    }
}

class CommuteViewModel(
    private val routeRepository: RouteRepository,
    private val sessionRepository: CommuteSessionRepository,
    private val stationRepository: StationRepository,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(CommuteUiState())
    val state = mutableState.asStateFlow()
    private data class InventoryTarget(val sessionId: UUID, val ids: Set<String>)
    private var inventoryTarget: InventoryTarget? = null
    private var inventoryJob: Job? = null

    init {
        viewModelScope.launch {
            routeRepository.observeRoutes().collect { routes ->
                mutableState.value = mutableState.value.copy(routes = routes)
            }
        }
        viewModelScope.launch {
            sessionRepository.observeSession().collect { session ->
                mutableState.value = mutableState.value.copy(session = session, busy = false)
                syncInventory(session)
            }
        }
    }

    fun start(route: CommuteRoute) = execute { sessionRepository.start(route, now()) }

    fun confirmBorrowed() {
        val session = mutableState.value.session ?: return
        execute {
            sessionRepository.dispatch(
                session.id,
                CommuteCommand.ConfirmBorrowed(
                    id = UUID.randomUUID(),
                    expectedRevision = session.revision,
                    at = now(),
                    station = session.selectedBorrow,
                ),
            )
        }
    }

    fun confirmReturned() {
        val session = mutableState.value.session ?: return
        execute {
            sessionRepository.dispatch(
                session.id,
                CommuteCommand.ConfirmReturned(
                    id = UUID.randomUUID(),
                    expectedRevision = session.revision,
                    at = now(),
                    station = session.selectedReturn,
                ),
            )
        }
    }

    fun cancel() {
        val session = mutableState.value.session ?: return
        execute {
            sessionRepository.dispatch(
                session.id,
                CommuteCommand.Cancel(
                    id = UUID.randomUUID(),
                    expectedRevision = session.revision,
                    at = now(),
                ),
            )
        }
    }

    fun selectBorrowStation(station: RouteStation) {
        val session = mutableState.value.session ?: return
        if (station.id == session.selectedBorrow.id) return
        execute {
            sessionRepository.dispatch(
                session.id,
                CommuteCommand.SelectBorrowStation(
                    id = UUID.randomUUID(),
                    expectedRevision = session.revision,
                    at = now(),
                    station = station,
                ),
            )
        }
    }

    fun selectReturnStation(station: RouteStation) {
        val session = mutableState.value.session ?: return
        if (station.id == session.selectedReturn.id) return
        execute {
            sessionRepository.dispatch(
                session.id,
                CommuteCommand.SelectReturnStation(
                    id = UUID.randomUUID(),
                    expectedRevision = session.revision,
                    at = now(),
                    station = station,
                ),
            )
        }
    }

    fun refreshAvailability() = refreshInventory(automatic = false)
    fun autoRefresh() = refreshInventory(automatic = true)

    private fun refreshInventory(automatic: Boolean) {
        val target = inventoryTarget ?: return
        if (inventoryJob?.isActive == true || mutableState.value.session?.hasInventory != true) return
        readInventory(target, refresh = true, showProgress = !automatic)
    }

    fun dismissFinished() {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, issue = null)
        viewModelScope.launch {
            runCatching { sessionRepository.clearTerminal() }
                .onFailure { mutableState.value = mutableState.value.copy(busy = false, issue = CommuteIssue.Storage) }
        }
    }

    fun clearIssue() {
        mutableState.value = mutableState.value.copy(issue = null)
    }

    private fun execute(block: suspend () -> CommuteSession) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, issue = null)
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { session ->
                    mutableState.value = mutableState.value.copy(session = session, busy = false)
                    syncInventory(session)
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        busy = false,
                        issue = if (error is IllegalStateException) CommuteIssue.InvalidAction else CommuteIssue.Storage,
                    )
                }
        }
    }

    private fun now(): Instant = clock.instant()

    private fun syncInventory(session: CommuteSession?) {
        val ids = buildSet {
            if (session?.isBorrowing == true) session.routeSnapshot.borrow.all.forEach { add(it.id.value) }
            if (session?.hasInventory == true) session.routeSnapshot.returns.all.forEach { add(it.id.value) }
        }
        val target = session?.takeIf { ids.isNotEmpty() }?.let { InventoryTarget(it.id, ids) }
        if (target == inventoryTarget) return
        inventoryJob?.cancel()
        val sameSession = inventoryTarget?.sessionId == target?.sessionId
        inventoryTarget = target
        val retained = if (sameSession) mutableState.value.mapStations.filter { it.id.value in ids } else emptyList()
        val failure = mutableState.value.inventoryFailure.takeIf { sameSession }
        val missing = if (sameSession) mutableState.value.unavailableStationIds.filterTo(hashSetOf()) { it.value in ids } else emptySet()
        mutableState.value = mutableState.value.copy(mapStations = retained, mapLoading = false,
            inventoryFailure = failure, unavailableStationIds = missing, mapIncomplete = failure != null || missing.isNotEmpty(), refreshingAvailability = false)
        if (target != null && (retained.map { it.id.value }.toSet() != ids || failure != null || missing.isNotEmpty())) readInventory(target, refresh = false)
    }

    private fun readInventory(target: InventoryTarget, refresh: Boolean, showProgress: Boolean = false) {
        mutableState.value = mutableState.value.copy(mapLoading = mutableState.value.mapStations.isEmpty(),
            refreshingAvailability = showProgress)
        inventoryJob = viewModelScope.launch {
            val refreshed = if (refresh) stationRepository.refresh() else StationResult.Success(Unit)
            ensureActive()
            val result = if (refreshed is StationResult.Failure) refreshed else stationRepository.getMany(target.ids)
            ensureActive()
            if (inventoryTarget != target) return@launch
            when (result) {
                is StationResult.Success -> {
                    val loaded = result.value.associateBy { it.id }
                    val missing = target.ids.map(::StationId).toSet() - loaded.keys
                    val retained = mutableState.value.mapStations.filter { it.id in missing }
                    mutableState.value = mutableState.value.copy(mapStations = result.value + retained,
                        mapLoading = false, refreshingAvailability = false, inventoryFailure = null,
                        unavailableStationIds = missing, mapIncomplete = missing.isNotEmpty())
                }
                is StationResult.Failure -> {
                    val now = clock.instant()
                    val retained = mutableState.value.mapStations.map { station ->
                        val observation = station.observation ?: return@map station
                        val freshness = AvailabilityFreshness.classify(observation.observedAt, now,
                            Duration.ofSeconds(observation.expectedIntervalSeconds))
                        if (freshness == observation.freshness) station else station.copy(observation = observation.copy(freshness = freshness))
                    }
                    mutableState.value = mutableState.value.copy(mapStations = retained, mapLoading = false,
                        refreshingAvailability = false, inventoryFailure = result.reason, mapIncomplete = true)
                }
            }
        }
    }
}

private val CommuteSession.isBorrowing get() = phase in setOf(CommutePhase.Preparing, CommutePhase.WalkingToBorrow)
private val CommuteSession.hasInventory get() = isBorrowing || phase in setOf(CommutePhase.Riding, CommutePhase.NearReturn)
