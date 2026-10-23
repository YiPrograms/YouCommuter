package dev.yikuo.youcommuter.feature.stations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.yikuo.youcommuter.core.domain.CoverageRepository
import dev.yikuo.youcommuter.core.domain.StationRepository
import dev.yikuo.youcommuter.core.domain.mapStations
import dev.yikuo.youcommuter.core.model.BikeStation
import dev.yikuo.youcommuter.core.model.CoverageResult
import dev.yikuo.youcommuter.core.model.FailureReason
import dev.yikuo.youcommuter.core.model.ServiceRegion
import dev.yikuo.youcommuter.core.model.StationResult
import dev.yikuo.youcommuter.core.model.StationPage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StationBrowserUiState(
    val showMap: Boolean = true,
    val regions: List<ServiceRegion> = emptyList(),
    val selectedRegion: String? = null,
    val query: String = "",
    val stations: List<BikeStation> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val failure: FailureReason? = null,
    val selectedStation: BikeStation? = null,
    val detailLoading: Boolean = false,
    val detailFailure: FailureReason? = null,
    val refreshing: Boolean = false,
)

class StationBrowserViewModel(
    private val coverageRepository: CoverageRepository,
    private val stationRepository: StationRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(StationBrowserUiState())
    val state = mutableState.asStateFlow()

    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var refreshJob: Job? = null
    private var generation = 0L

    init {
        loadRegions()
    }

    fun selectRegion(code: String) {
        if (code == mutableState.value.selectedRegion || mutableState.value.regions.none { it.code == code }) return
        mutableState.value = mutableState.value.copy(selectedRegion = code)
        scheduleSearch(delayMillis = 0)
    }

    fun updateQuery(query: String) {
        if (query == mutableState.value.query) return
        mutableState.value = mutableState.value.copy(query = query)
        scheduleSearch(delayMillis = SearchDebounceMillis)
    }

    fun refresh() {
        val current = mutableState.value
        if (current.loading || refreshJob?.isActive == true) return
        if (current.selectedRegion == null) loadRegions()
        else if (current.stations.isEmpty()) scheduleSearch(0, forceRefresh = true)
        else refreshInventory(automatic = false)
    }

    fun autoRefresh() {
        if (!mutableState.value.loading && mutableState.value.stations.isNotEmpty()) refreshInventory(automatic = true)
    }

    private fun refreshInventory(automatic: Boolean) {
        if (refreshJob?.isActive == true) return
        val requestGeneration = generation
        val ids = mutableState.value.stations.mapTo(linkedSetOf()) { it.id.value }
        detailJob?.cancel()
        mutableState.value = mutableState.value.copy(refreshing = !automatic, detailLoading = false)
        refreshJob = viewModelScope.launch {
            val refreshed = stationRepository.refresh()
            ensureActive()
            val result = if (refreshed is StationResult.Failure) refreshed else stationRepository.getMany(ids)
            ensureActive()
            if (generation != requestGeneration) return@launch
            when (result) {
                is StationResult.Success -> {
                    val updates = result.value.associateBy { it.id }
                    // Preserve identities, geometry, order, paging and the open preview.
                    fun updated(station: BikeStation) = station.copy(observation = updates[station.id]?.observation)
                    mutableState.value = mutableState.value.copy(
                        stations = mutableState.value.stations.map { if (it.id.value in ids) updated(it) else it },
                        selectedStation = mutableState.value.selectedStation?.let(::updated),
                        refreshing = false, failure = null, detailFailure = null)
                }
                is StationResult.Failure -> mutableState.value = mutableState.value.copy(
                    refreshing = false, failure = result.reason,
                    detailFailure = result.reason.takeIf { mutableState.value.selectedStation != null })
            }
        }
    }

    fun toggleMap() {
        val current = mutableState.value
        val showMap = !current.showMap
        mutableState.value = current.copy(showMap = showMap)
        if (showMap && current.nextCursor != null && !current.loading) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch { performSearch(current.selectedRegion ?: return@launch, current.query) }
        }
    }

    fun loadMore() {
        val current = mutableState.value
        val region = current.selectedRegion ?: return
        val cursor = current.nextCursor ?: return
        if (current.loading || current.loadingMore) return
        searchJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loadingMore = true, failure = null)
            val result = stationRepository.search(region, current.query.trim(), cursor)
            ensureActive()
            when (result) {
                is StationResult.Success -> {
                    val combined = (mutableState.value.stations + result.value.stations).distinctBy { it.id }
                    mutableState.value = mutableState.value.copy(
                        stations = combined,
                        nextCursor = result.value.nextCursor,
                        loadingMore = false,
                    )
                }
                is StationResult.Failure -> mutableState.value = mutableState.value.copy(
                    loadingMore = false,
                    failure = result.reason,
                )
            }
        }
    }

    fun openStation(station: BikeStation) = loadDetail(station, forceRefresh = false)

    private fun loadDetail(station: BikeStation, forceRefresh: Boolean) {
        if (mutableState.value.stations.none { it.id == station.id }) return
        detailJob?.cancel()
        mutableState.value = mutableState.value.copy(
            selectedStation = station,
            detailLoading = true,
            detailFailure = null,
        )
        detailJob = viewModelScope.launch {
            if (forceRefresh) {
                val refreshed = stationRepository.refresh()
                ensureActive()
                if (refreshed is StationResult.Failure) {
                    mutableState.value = mutableState.value.copy(detailLoading = false, detailFailure = refreshed.reason)
                    return@launch
                }
            }
            val result = stationRepository.get(station.id.value)
            ensureActive()
            when (result) {
                is StationResult.Success -> mutableState.value = mutableState.value.copy(
                    selectedStation = result.value,
                    detailLoading = false,
                )
                is StationResult.Failure -> mutableState.value = mutableState.value.copy(
                    detailLoading = false,
                    detailFailure = result.reason,
                )
            }
        }
    }

    fun retryDetail() {
        if (mutableState.value.selectedStation != null) refresh()
    }

    fun closeDetail() {
        detailJob?.cancel()
        mutableState.value = mutableState.value.copy(
            selectedStation = null,
            detailLoading = false,
            detailFailure = null,
        )
    }

    private fun loadRegions() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, failure = null)
            when (val result = coverageRepository.getCoverage()) {
                is CoverageResult.Success -> {
                    val selected = result.regions.firstOrNull { it.code == PreferredInitialRegion }?.code
                        ?: result.regions.firstOrNull()?.code
                    mutableState.value = mutableState.value.copy(
                        regions = result.regions,
                        selectedRegion = selected,
                        loading = selected != null,
                        stations = emptyList(),
                        nextCursor = null,
                    )
                    if (selected != null) performSearch(selected, mutableState.value.query)
                }
                is CoverageResult.Failure -> mutableState.value = mutableState.value.copy(
                    loading = false,
                    failure = result.reason,
                )
            }
        }
    }

    private fun scheduleSearch(delayMillis: Long, forceRefresh: Boolean = false) {
        searchJob?.cancel()
        refreshJob?.cancel()
        generation++
        closeDetail()
        val region = mutableState.value.selectedRegion ?: return
        mutableState.value = mutableState.value.copy(
            stations = emptyList(),
            nextCursor = null,
            loading = true,
            loadingMore = false,
            refreshing = false,
            failure = null,
        )
        searchJob = viewModelScope.launch {
            delay(delayMillis)
            if (forceRefresh) {
                val refreshed = stationRepository.refresh()
                ensureActive()
                if (refreshed is StationResult.Failure) {
                    mutableState.value = mutableState.value.copy(loading = false, failure = refreshed.reason)
                    return@launch
                }
            }
            performSearch(region, mutableState.value.query)
        }
    }

    private suspend fun performSearch(region: String, query: String) {
        val result = if (mutableState.value.showMap) {
            when (val all = stationRepository.mapStations(region, query)) {
                is StationResult.Success -> StationResult.Success(StationPage(all.value, null))
                is StationResult.Failure -> all
            }
        } else stationRepository.search(region, query.trim())
        currentCoroutineContext().ensureActive()
        when (result) {
            is StationResult.Success -> mutableState.value = mutableState.value.copy(
                stations = result.value.stations,
                nextCursor = result.value.nextCursor,
                loading = false,
                failure = null,
            )
            is StationResult.Failure -> mutableState.value = mutableState.value.copy(
                loading = false,
                failure = result.reason,
            )
        }
    }

    private companion object {
        const val PreferredInitialRegion = "00"
        const val SearchDebounceMillis = 300L
    }
}
