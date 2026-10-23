package dev.yikuo.youcommuter.feature.routes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.yikuo.youcommuter.core.domain.CoverageRepository
import dev.yikuo.youcommuter.core.domain.RouteRepository
import dev.yikuo.youcommuter.core.domain.StationRepository
import dev.yikuo.youcommuter.core.model.BikeKind
import dev.yikuo.youcommuter.core.model.StationLocation
import dev.yikuo.youcommuter.core.model.AvailabilityThresholdRange
import dev.yikuo.youcommuter.core.model.CommuteRoute
import dev.yikuo.youcommuter.core.model.CoverageResult
import dev.yikuo.youcommuter.core.model.FailureReason
import dev.yikuo.youcommuter.core.model.DefaultLowBikeThreshold
import dev.yikuo.youcommuter.core.model.DefaultLowDockThreshold
import dev.yikuo.youcommuter.core.model.EstimatedReturnMinutesRange
import dev.yikuo.youcommuter.core.model.RouteStation
import dev.yikuo.youcommuter.core.model.ServiceRegion
import dev.yikuo.youcommuter.core.model.StationGroup
import dev.yikuo.youcommuter.core.model.StationResult
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class StationTarget { BorrowPrimary, ReturnPrimary, BorrowBackup, ReturnBackup }
enum class BackupGroup { Borrow, Return }
enum class RouteEditorIssue { MissingName, MissingStation, InvalidTime, InvalidReturnMinutes, Storage }

data class RouteEditorState(
    val id: UUID? = null,
    val revision: Long = 0,
    val name: String = "",
    val departure: String = "08:30",
    val weekdays: Set<DayOfWeek> = DayOfWeek.entries.take(5).toSet(),
    val borrow: RouteStation? = null,
    val returns: RouteStation? = null,
    val borrowBackups: List<RouteStation> = emptyList(),
    val returnBackups: List<RouteStation> = emptyList(),
    val reminderMinutesBefore: Int? = null,
    val lowBikeThreshold: Int = DefaultLowBikeThreshold,
    val lowDockThreshold: Int = DefaultLowDockThreshold,
    val estimatedReturnMinutes: String = "",
    val bikeDisplay: BikeKind = BikeKind.All,
    val bikeThreshold: BikeKind = BikeKind.All,
    val issue: RouteEditorIssue? = null,
    val saving: Boolean = false,
)

data class StationPickerState(
    val target: StationTarget,
    val regions: List<ServiceRegion>,
    val selectedRegion: String,
    val excludedStationIds: Set<String>,
    val query: String = "",
    val stations: List<StationLocation> = emptyList(),
    val loading: Boolean = true,
    val failure: FailureReason? = null,
    val showMap: Boolean = true,
    val primaryReference: RouteStation? = null,
    val primary: StationLocation? = null,
    val followPrimary: Boolean = true,
    val mapCameraKey: String = "picker",
)

data class RoutesUiState(
    val routes: List<CommuteRoute> = emptyList(),
    val editor: RouteEditorState? = null,
    val picker: StationPickerState? = null,
    val pendingDelete: CommuteRoute? = null,
    val confirmDiscard: Boolean = false,
)

class RoutesViewModel(
    private val coverageRepository: CoverageRepository,
    private val stationRepository: StationRepository,
    private val routeRepository: RouteRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RoutesUiState())
    val state = mutableState.asStateFlow()
    private var regions: List<ServiceRegion> = emptyList()
    private var selectedRegion = PreferredRegion
    private var pickerJob: Job? = null
    private var originalEditor: RouteEditorState? = null
    private var pickerShowsMap = true

    init {
        viewModelScope.launch {
            routeRepository.observeRoutes().collect { routes ->
                mutableState.value = mutableState.value.copy(routes = routes)
            }
        }
        viewModelScope.launch {
            val result = coverageRepository.getCoverage()
            if (result is CoverageResult.Success) {
                regions = result.regions
                selectedRegion = regions.firstOrNull { it.code == PreferredRegion }?.code
                    ?: regions.firstOrNull()?.code
                    ?: PreferredRegion
                mutableState.value.picker?.let { picker ->
                    val region = if (picker.primaryReference == null && regions.none { it.code == picker.selectedRegion })
                        selectedRegion else picker.selectedRegion
                    mutableState.value = mutableState.value.copy(picker = picker.copy(regions = regions, selectedRegion = region))
                    if (picker.selectedRegion != region) scheduleSearch(0)
                }
            }
        }
    }

    fun addRoute() {
        openEditor(RouteEditorState())
    }

    fun editRoute(route: CommuteRoute) = openEditor(editorFor(route))

    fun duplicateRoute(route: CommuteRoute, name: String) = openEditor(
        editorFor(route).copy(id = null, revision = 0, name = name.take(40)),
    )

    fun swapStations() = updateEditor {
        copy(borrow = returns, returns = borrow, borrowBackups = returnBackups, returnBackups = borrowBackups, issue = null)
    }

    fun setBikeDisplay(kind: BikeKind) = updateEditor {
        copy(bikeDisplay = kind, bikeThreshold = if (kind == BikeKind.All) bikeThreshold else kind)
    }
    fun setBikeThreshold(kind: BikeKind) = updateEditor {
        if (bikeDisplay == BikeKind.All) copy(bikeThreshold = kind) else this
    }

    private fun editorFor(route: CommuteRoute) = RouteEditorState(
        id = route.id, revision = route.revision, name = route.name,
        departure = route.departure.toString(), weekdays = route.weekdays,
        borrow = route.borrow.primary, returns = route.returns.primary,
        borrowBackups = route.borrow.backups, returnBackups = route.returns.backups,
        reminderMinutesBefore = route.reminderMinutesBefore,
        lowBikeThreshold = route.lowBikeThreshold, lowDockThreshold = route.lowDockThreshold,
        estimatedReturnMinutes = route.estimatedReturnMinutes?.toString().orEmpty(),
        bikeDisplay = route.bikeDisplay, bikeThreshold = route.bikeThreshold,
    )

    private fun openEditor(editor: RouteEditorState) {
        pickerJob?.cancel()
        originalEditor = editor
        mutableState.value = mutableState.value.copy(editor = editor, picker = null, confirmDiscard = false)
    }

    fun closeEditor() {
        if (mutableState.value.picker != null) {
            closePicker()
            return
        }
        val editor = mutableState.value.editor ?: return
        if (editor.saving) return
        if (editor.copy(issue = null, saving = false) != originalEditor) {
            mutableState.value = mutableState.value.copy(confirmDiscard = true)
        } else {
            discardEditor()
        }
    }

    fun keepEditing() {
        mutableState.value = mutableState.value.copy(confirmDiscard = false)
    }

    fun discardEditor() {
        if (mutableState.value.editor?.saving == true) return
        pickerJob?.cancel()
        originalEditor = null
        mutableState.value = mutableState.value.copy(editor = null, picker = null, confirmDiscard = false)
    }

    fun updateName(value: String) = updateEditor { copy(name = value.take(40), issue = null) }
    fun updateDeparture(value: String) = updateEditor { copy(departure = value.take(5), issue = null) }
    fun updateEstimatedReturnMinutes(value: String) = updateEditor {
        copy(estimatedReturnMinutes = value.take(3), issue = null)
    }

    fun toggleWeekday(day: DayOfWeek) = updateEditor {
        copy(weekdays = if (day in weekdays) weekdays - day else weekdays + day, issue = null)
    }

    fun setReminderEnabled(enabled: Boolean) = updateEditor {
        copy(reminderMinutesBefore = if (enabled) reminderMinutesBefore ?: DefaultReminderMinutes else null)
    }

    fun setReminderLead(minutes: Int) {
        if (minutes !in ReminderLeadOptions) return
        updateEditor { copy(reminderMinutesBefore = minutes) }
    }

    fun setLowBikeThreshold(value: Int) = updateEditor {
        copy(lowBikeThreshold = value.coerceIn(AvailabilityThresholdRange))
    }

    fun setLowDockThreshold(value: Int) = updateEditor {
        copy(lowDockThreshold = value.coerceIn(AvailabilityThresholdRange))
    }

    fun openPicker(target: StationTarget) {
        val editor = mutableState.value.editor ?: return
        if (target == StationTarget.BorrowBackup && editor.borrowBackups.size >= MaxBackups) return
        if (target == StationTarget.ReturnBackup && editor.returnBackups.size >= MaxBackups) return
        val excluded = when (target) {
            StationTarget.BorrowPrimary -> listOfNotNull(editor.borrow).mapTo(mutableSetOf()) { it.id.value }
            StationTarget.ReturnPrimary -> listOfNotNull(editor.returns).mapTo(mutableSetOf()) { it.id.value }
            StationTarget.BorrowBackup ->
                listOfNotNull(editor.borrow).plus(editor.borrowBackups).mapTo(mutableSetOf()) { it.id.value }
            StationTarget.ReturnBackup ->
                listOfNotNull(editor.returns).plus(editor.returnBackups).mapTo(mutableSetOf()) { it.id.value }
        }
        mutableState.value = mutableState.value.copy(
            picker = StationPickerState(target, regions, selectedRegion, excluded, showMap = pickerShowsMap,
                primaryReference = when (target) {
                    StationTarget.BorrowBackup -> editor.borrow
                    StationTarget.ReturnBackup -> editor.returns
                    else -> null
                }),
        )
        scheduleSearch(0)
    }

    fun closePicker() {
        pickerJob?.cancel()
        mutableState.value = mutableState.value.copy(picker = null)
    }

    fun togglePickerMap() {
        val picker = mutableState.value.picker ?: return
        pickerShowsMap = !picker.showMap
        mutableState.value = mutableState.value.copy(picker = picker.copy(showMap = pickerShowsMap))
    }

    fun selectRegion(code: String) {
        val picker = mutableState.value.picker ?: return
        if (code == picker.selectedRegion) return
        selectedRegion = code
        mutableState.value = mutableState.value.copy(picker = picker.copy(selectedRegion = code, followPrimary = false))
        scheduleSearch(0)
    }

    fun updateStationQuery(query: String) {
        val picker = mutableState.value.picker ?: return
        mutableState.value = mutableState.value.copy(picker = picker.copy(query = query))
        scheduleSearch(SearchDebounceMillis)
    }

    fun retryStationSearch() = scheduleSearch(0)

    fun selectStation(station: StationLocation) {
        val picker = mutableState.value.picker ?: return
        if (station.id.value in picker.excludedStationIds || picker.stations.none { it.id == station.id }) return
        val target = picker.target
        val selection = RouteStation(station.id, station.name)
        updateEditor {
            when (target) {
                StationTarget.BorrowPrimary -> copy(
                    borrow = selection,
                    borrowBackups = borrowBackups.filterNot { it.id == selection.id },
                    issue = null,
                )
                StationTarget.ReturnPrimary -> copy(
                    returns = selection,
                    returnBackups = returnBackups.filterNot { it.id == selection.id },
                    issue = null,
                )
                StationTarget.BorrowBackup -> copy(
                    borrowBackups = (borrowBackups + selection).distinctBy { it.id }.take(MaxBackups),
                    issue = null,
                )
                StationTarget.ReturnBackup -> copy(
                    returnBackups = (returnBackups + selection).distinctBy { it.id }.take(MaxBackups),
                    issue = null,
                )
            }
        }
        closePicker()
    }

    fun removeBackup(group: BackupGroup, station: RouteStation) = updateEditor {
        when (group) {
            BackupGroup.Borrow -> copy(borrowBackups = borrowBackups.filterNot { it.id == station.id })
            BackupGroup.Return -> copy(returnBackups = returnBackups.filterNot { it.id == station.id })
        }
    }

    fun save() {
        val editor = mutableState.value.editor ?: return
        if (editor.saving) return
        val name = editor.name.trim()
        val departure = try {
            LocalTime.parse(editor.departure.trim())
        } catch (_: DateTimeParseException) {
            updateEditor { copy(issue = RouteEditorIssue.InvalidTime) }
            return
        }
        if (name.isBlank()) {
            updateEditor { copy(issue = RouteEditorIssue.MissingName) }
            return
        }
        val borrow = editor.borrow
        val returns = editor.returns
        if (borrow == null || returns == null) {
            updateEditor { copy(issue = RouteEditorIssue.MissingStation) }
            return
        }
        val returnInput = editor.estimatedReturnMinutes.trim()
        val returnMinutes = returnInput.toIntOrNull()
        if (returnInput.isNotEmpty() && (returnMinutes == null || returnMinutes !in EstimatedReturnMinutesRange)) {
            updateEditor { copy(issue = RouteEditorIssue.InvalidReturnMinutes) }
            return
        }
        val route = CommuteRoute(
            id = editor.id ?: UUID.randomUUID(),
            name = name,
            borrow = StationGroup(borrow, editor.borrowBackups),
            returns = StationGroup(returns, editor.returnBackups),
            departure = departure,
            weekdays = editor.weekdays,
            revision = editor.revision + 1,
            reminderMinutesBefore = editor.reminderMinutesBefore,
            lowBikeThreshold = editor.lowBikeThreshold,
            lowDockThreshold = editor.lowDockThreshold,
            estimatedReturnMinutes = returnMinutes,
            bikeDisplay = editor.bikeDisplay, bikeThreshold = editor.bikeThreshold,
        )
        updateEditor { copy(saving = true, issue = null) }
        viewModelScope.launch {
            runCatching { routeRepository.save(route) }
                .onSuccess {
                    originalEditor = null
                    mutableState.value = mutableState.value.copy(editor = null, confirmDiscard = false)
                }
                .onFailure { updateEditor { copy(saving = false, issue = RouteEditorIssue.Storage) } }
        }
    }

    fun requestDelete(route: CommuteRoute) {
        mutableState.value = mutableState.value.copy(pendingDelete = route)
    }

    fun cancelDelete() {
        mutableState.value = mutableState.value.copy(pendingDelete = null)
    }

    fun confirmDelete() {
        val route = mutableState.value.pendingDelete ?: return
        mutableState.value = mutableState.value.copy(pendingDelete = null)
        viewModelScope.launch { routeRepository.delete(route.id) }
    }

    private fun updateEditor(transform: RouteEditorState.() -> RouteEditorState) {
        val editor = mutableState.value.editor ?: return
        mutableState.value = mutableState.value.copy(editor = editor.transform())
    }

    private fun scheduleSearch(delayMillis: Long) {
        pickerJob?.cancel()
        val picker = mutableState.value.picker ?: return
        mutableState.value = mutableState.value.copy(
            picker = picker.copy(loading = true, failure = null),
        )
        pickerJob = viewModelScope.launch {
            delay(delayMillis)
            var current = mutableState.value.picker ?: return@launch
            if (current.primary == null && current.primaryReference != null) {
                val primary = stationRepository.location(current.primaryReference!!.id.value)
                ensureActive()
                when (primary) {
                    is StationResult.Failure -> {
                        mutableState.value = mutableState.value.copy(picker = mutableState.value.picker?.copy(
                            loading = false, failure = primary.reason))
                        return@launch
                    }
                    is StationResult.Success -> {
                        val active = mutableState.value.picker ?: return@launch
                        val region = if (active.followPrimary) primary.value.regionCode else active.selectedRegion
                        current = active.copy(primary = primary.value, selectedRegion = region)
                        selectedRegion = region
                        mutableState.value = mutableState.value.copy(picker = current)
                    }
                }
            }
            val result = stationRepository.locations(current.selectedRegion, current.query)
            ensureActive()
            when (result) {
                is StationResult.Success -> mutableState.value = mutableState.value.copy(
                    picker = mutableState.value.picker?.copy(
                        stations = result.value.filterNot { it.id.value in current.excludedStationIds && it.id != current.primary?.id },
                        mapCameraKey = "${current.selectedRegion}:${current.query}",
                        loading = false,
                        failure = null,
                    ),
                )
                is StationResult.Failure -> mutableState.value = mutableState.value.copy(
                    picker = mutableState.value.picker?.copy(loading = false, failure = result.reason),
                )
            }
        }
    }

    private companion object {
        const val PreferredRegion = "00"
        const val SearchDebounceMillis = 300L
        const val MaxBackups = 5
        const val DefaultReminderMinutes = 15
        val ReminderLeadOptions = setOf(0, 5, 10, 15, 30)
    }
}
