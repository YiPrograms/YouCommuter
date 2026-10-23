package dev.yikuo.youcommuter.feature.stations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.yikuo.youcommuter.core.domain.CoverageRepository
import dev.yikuo.youcommuter.core.domain.StationRepository
import dev.yikuo.youcommuter.core.ui.AutoRefreshEffect
import dev.yikuo.youcommuter.core.ui.AppIcon
import dev.yikuo.youcommuter.core.ui.AppIconButton
import dev.yikuo.youcommuter.core.ui.InventoryCounts
import dev.yikuo.youcommuter.core.ui.AppIconType
import dev.yikuo.youcommuter.core.ui.IconText
import dev.yikuo.youcommuter.core.model.BikeStation
import dev.yikuo.youcommuter.core.model.FailureReason
import dev.yikuo.youcommuter.core.model.Freshness
import dev.yikuo.youcommuter.core.model.StationServiceStatus
import dev.yikuo.youcommuter.core.map.MapMetric
import dev.yikuo.youcommuter.core.map.MapMetricSelector
import dev.yikuo.youcommuter.core.map.MapViewToggle
import dev.yikuo.youcommuter.core.map.StationMap
import dev.yikuo.youcommuter.core.map.mapPoint
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.ZoneId
import java.util.Locale

@Composable
fun StationBrowserRoute(
    coverageRepository: CoverageRepository,
    stationRepository: StationRepository,
    modifier: Modifier = Modifier,
) {
    val model: StationBrowserViewModel = viewModel(factory = viewModelFactory {
        initializer { StationBrowserViewModel(coverageRepository, stationRepository) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    AutoRefreshEffect(model::autoRefresh)
    StationBrowserScreen(
        state = state,
        onRegionSelected = model::selectRegion,
        onQueryChanged = model::updateQuery,
        onRefresh = model::refresh,
        onLoadMore = model::loadMore,
        onStationSelected = model::openStation,
        onDetailRetry = model::retryDetail,
        onDetailDismissed = model::closeDetail,
        onMapToggled = model::toggleMap,
        modifier = modifier,
    )
}

@Composable
fun StationBrowserScreen(
    state: StationBrowserUiState,
    onRegionSelected: (String) -> Unit,
    onQueryChanged: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onStationSelected: (BikeStation) -> Unit,
    onDetailRetry: () -> Unit,
    onDetailDismissed: () -> Unit,
    onMapToggled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val regionScroll = rememberLazyListState()
    val stationScroll = rememberLazyListState()
    val mapStateHolder = rememberSaveableStateHolder()
    var listRegion by rememberSaveable { mutableStateOf(state.selectedRegion) }
    var listQuery by rememberSaveable { mutableStateOf(state.query) }
    LaunchedEffect(state.selectedRegion, state.query) {
        if (listRegion != state.selectedRegion || listQuery != state.query) {
            stationScroll.scrollToItem(0)
            listRegion = state.selectedRegion
            listQuery = state.query
        }
    }
    var metric by rememberSaveable { mutableStateOf(MapMetric.Bikes) }
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = state.selectedStation != null, onBack = onDetailDismissed)
    LaunchedEffect(state.regions, state.selectedRegion) {
        val index = state.regions.indexOfFirst { it.code == state.selectedRegion }
        if (index >= 0) regionScroll.animateScrollToItem(index)
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.stations_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            MapViewToggle(state.showMap, onMapToggled)
            AppIconButton(if (state.failure == null) AppIconType.Refresh else AppIconType.Warning, stringResource(R.string.refresh), onRefresh,
                enabled = !state.loading && !state.refreshing && state.selectedRegion != null)
        }
        LazyRow(
            state = regionScroll,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.regions, key = { it.code }) { region ->
                FilterChip(
                    selected = region.code == state.selectedRegion,
                    onClick = { onRegionSelected(region.code) },
                    label = { Text(region.name) },
                )
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            enabled = state.selectedRegion != null,
            singleLine = true,
            label = { Text(stringResource(R.string.search_station)) },
            leadingIcon = { AppIcon(AppIconType.Search) },
            trailingIcon = {
                if (state.query.isNotEmpty()) AppIconButton(AppIconType.Close, stringResource(R.string.clear_search), { onQueryChanged("") })
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        )
        if (state.showMap) MapMetricSelector(metric, { metric = it }, Modifier.padding(horizontal = 16.dp))
        when {
            state.loading -> LoadingContent()
            state.failure != null && state.stations.isEmpty() -> FailureContent(state.failure, onRefresh)
            state.regions.isEmpty() -> EmptyContent(R.string.no_regions)
            state.stations.isEmpty() -> EmptyContent(R.string.no_stations)
            state.showMap -> mapStateHolder.SaveableStateProvider("station-map") { StationMap(
                points = state.stations.map { station ->
                    val selected = station.id == state.selectedStation?.id
                    (if (selected) requireNotNull(state.selectedStation) else station).mapPoint(metric, selected)
                },
                onStationSelected = { point ->
                    focusManager.clearFocus()
                    state.stations.firstOrNull { it.id.value == point.stationId }?.let(onStationSelected)
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                cameraKey = "${state.selectedRegion}:${state.query}",
            ) }
            else -> LazyColumn(
                state = stationScroll,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.stations, key = { it.id.value }) { station ->
                    StationCard(station, { onStationSelected(station) }, Modifier.padding(horizontal = 16.dp))
                }
                if (state.nextCursor != null) {
                    item {
                        Button(
                            onClick = onLoadMore,
                            enabled = !state.loadingMore,
                            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                        ) {
                            if (state.loadingMore) {
                                CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                            } else {
                                AppIcon(AppIconType.ExpandMore)
                                Text(stringResource(R.string.load_more), Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
        state.selectedStation?.let { station ->
            StationDetailCard(station, state.detailLoading, state.detailFailure, onDetailRetry, onDetailDismissed)
        }
    }
}

@Composable
private fun StationCard(station: BikeStation, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(station.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (station.address.isNotBlank()) Text(station.address, style = MaterialTheme.typography.bodySmall)
            AvailabilityRow(station)
            if (station.observation?.freshness != Freshness.Fresh || station.observation?.serviceStatus != StationServiceStatus.Operating) {
                IconText(stationStatusIcon(station), stationStatus(station), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun AvailabilityRow(station: BikeStation) {
    val counts = station.observation?.counts
    InventoryCounts(counts?.regularBikes, counts?.electricBikes, counts?.returnDocks)
}

private fun stationStatusIcon(station: BikeStation): AppIconType {
    val observation = station.observation ?: return AppIconType.Warning
    if (observation.serviceStatus != StationServiceStatus.Operating) return AppIconType.Warning
    return when (observation.freshness) {
        Freshness.Fresh -> AppIconType.Check
        Freshness.Aging -> AppIconType.Clock
        Freshness.Stale, Freshness.Unavailable -> AppIconType.Warning
    }
}

@Composable
private fun stationStatus(station: BikeStation): String {
    val observation = station.observation ?: return stringResource(R.string.no_current_data)
    return stringResource(when (observation.serviceStatus) {
        StationServiceStatus.TemporarilyClosed -> R.string.status_closed
        StationServiceStatus.Retired -> R.string.status_retired
        StationServiceStatus.Unknown -> R.string.status_unknown
        StationServiceStatus.Operating -> when (observation.freshness) {
            Freshness.Fresh -> R.string.freshness_fresh
            Freshness.Aging -> R.string.freshness_aging
            Freshness.Stale -> R.string.freshness_stale
            Freshness.Unavailable -> R.string.freshness_unavailable
        }
    })
}

@Composable
private fun StationDetailCard(
    station: BikeStation,
    loading: Boolean,
    failure: FailureReason?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(12.dp).heightIn(max = 240.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(station.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                AppIconButton(AppIconType.Close, stringResource(R.string.close), onDismiss)
            }
            if (station.address.isNotBlank()) Text(station.address, style = MaterialTheme.typography.bodySmall)
            AvailabilityRow(station)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                if (failure != null) {
                    IconText(AppIconType.Warning, failureText(failure), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    AppIconButton(AppIconType.Refresh, stringResource(R.string.retry), onRetry)
                } else {
                    val updated = station.observation?.let { formatInstant(it.observedAt ?: it.providerUpdatedAt) } ?: "—"
                    IconText(if (loading) AppIconType.Refresh else stationStatusIcon(station), updated,
                        description = stringResource(R.string.updated_at, updated), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
    }
}

@Composable
private fun FailureContent(reason: FailureReason, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(failureText(reason))
        AppIconButton(AppIconType.Refresh, stringResource(R.string.retry), onRetry)
    }
}

@Composable
private fun EmptyContent(message: Int) {
    Text(stringResource(message), Modifier.padding(16.dp))
}

@Composable
private fun failureText(reason: FailureReason): String = stringResource(when (reason) {
    FailureReason.NotConfigured -> R.string.not_configured
    FailureReason.Network -> R.string.network_error
    FailureReason.Service -> R.string.service_error
    FailureReason.InvalidResponse -> R.string.response_error
})

private fun formatInstant(instant: java.time.Instant): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.SHORT)
    .withLocale(Locale.getDefault())
    .withZone(ZoneId.systemDefault())
    .format(instant)
