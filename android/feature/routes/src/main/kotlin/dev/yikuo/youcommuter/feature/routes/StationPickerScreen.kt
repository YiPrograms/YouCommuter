package dev.yikuo.youcommuter.feature.routes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yikuo.youcommuter.core.map.MapFocus
import dev.yikuo.youcommuter.core.map.MapPointRole
import dev.yikuo.youcommuter.core.map.MapViewToggle
import dev.yikuo.youcommuter.core.map.StationMap
import dev.yikuo.youcommuter.core.map.mapPoint
import dev.yikuo.youcommuter.core.model.FailureReason
import dev.yikuo.youcommuter.core.model.StationLocation
import dev.yikuo.youcommuter.core.ui.AppIcon
import dev.yikuo.youcommuter.core.ui.AppIconButton
import dev.yikuo.youcommuter.core.ui.AppIconType
import dev.yikuo.youcommuter.core.map.R as MapR

@Composable
internal fun StationPickerScreen(
    state: StationPickerState,
    onDismiss: () -> Unit,
    onRegionSelected: (String) -> Unit,
    onQueryChanged: (String) -> Unit,
    onStationSelected: (StationLocation) -> Unit,
    onRetry: () -> Unit,
    onMapToggled: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val focus = LocalFocusManager.current
    var previewId by rememberSaveable(state.target, state.selectedRegion, state.query) { mutableStateOf<String?>(null) }
    val preview = state.stations.firstOrNull { it.id.value == previewId && it.id != state.primary?.id }
    val stations = remember(state.stations, state.primary) {
        listOfNotNull(state.primary) + state.stations.filterNot { it.id == state.primary?.id }
    }
    val points = remember(stations, state.primary) {
        stations.map { station -> station.mapPoint().let {
            if (station.id == state.primary?.id) it.copy(selectable = false, role = MapPointRole.Primary) else it
        } }
    }
    val canSelect = !state.loading && state.failure == null
    BackHandler(enabled = active && preview != null) { previewId = null }
    val regionScroll = rememberLazyListState()
    val stationScroll = rememberLazyListState()
    val mapStateHolder = rememberSaveableStateHolder()
    LaunchedEffect(state.selectedRegion, state.query) { stationScroll.scrollToItem(0) }
    LaunchedEffect(state.regions, state.selectedRegion) {
        val index = state.regions.indexOfFirst { it.code == state.selectedRegion }
        if (index >= 0) regionScroll.animateScrollToItem(index)
    }
    Column(modifier.fillMaxSize().imePadding()) {
        EditorHeader(stationPickerTitle(state.target), { if (preview != null) previewId = null else onDismiss() }) {
            MapViewToggle(state.showMap) { previewId = null; onMapToggled() }
        }
        if (state.regions.isNotEmpty()) LazyRow(state = regionScroll, contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.regions, key = { it.code }) { region ->
                FilterChip(selected = region.code == state.selectedRegion, onClick = { onRegionSelected(region.code) },
                    label = { Text(region.name) })
            }
        }
        OutlinedTextField(value = state.query, onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            label = { Text(stringResource(R.string.search_station)) }, singleLine = true,
            leadingIcon = { AppIcon(AppIconType.Search) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            trailingIcon = {
                if (state.query.isNotEmpty()) AppIconButton(AppIconType.Close, stringResource(R.string.clear_search), { onQueryChanged("") })
            })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.showMap && points.isNotEmpty() -> mapStateHolder.SaveableStateProvider("picker-map") { StationMap(points,
                    onStationSelected = { if (canSelect && it.selectable) { focus.clearFocus(); previewId = it.stationId } },
                    modifier = Modifier.fillMaxSize(), cameraKey = state.mapCameraKey,
                    selectedStationId = previewId,
                    initialFocus = state.primary?.takeIf { it.regionCode == state.selectedRegion && state.query.isBlank() }
                        ?.let { MapFocus(it.latitude, it.longitude) }) }
                state.loading && stations.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.failure != null && stations.isEmpty() -> Column(Modifier.padding(16.dp)) {
                    Text(failureText(state.failure), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
                stations.isEmpty() -> Text(stringResource(R.string.no_station_results), Modifier.padding(16.dp))
                else -> LazyColumn(Modifier.fillMaxSize(), state = stationScroll, contentPadding = PaddingValues(horizontal = 16.dp)) {
                    items(stations, key = { it.id.value }) { station ->
                        val primary = station.id == state.primary?.id
                        Surface(onClick = { focus.clearFocus(); onStationSelected(station) }, enabled = !primary && canSelect,
                            color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(station.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (primary) Text(stringResource(R.string.primary_station), color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelMedium)
                                }
                                if (station.address.isNotBlank()) Text(station.address, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            if (state.loading && stations.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            if (state.failure != null && stations.isNotEmpty()) Surface(Modifier.align(Alignment.TopStart).padding(8.dp),
                shape = MaterialTheme.shapes.medium) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(failureText(state.failure), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall)
                    AppIconButton(AppIconType.Refresh, stringResource(R.string.retry), onRetry)
                }
            }
        }
        if (state.showMap && preview != null) Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(preview.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    AppIconButton(AppIconType.Close, stringResource(MapR.string.map_close_preview), { previewId = null })
                }
                if (preview.address.isNotBlank()) Text(preview.address, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { focus.clearFocus(); onStationSelected(preview) }, enabled = canSelect, modifier = Modifier.fillMaxWidth()) {
                    AppIcon(AppIconType.Check)
                    Text(stringResource(MapR.string.map_select_station), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun stationPickerTitle(target: StationTarget): String = stringResource(when (target) {
    StationTarget.BorrowPrimary -> R.string.borrow_station
    StationTarget.ReturnPrimary -> R.string.return_station
    StationTarget.BorrowBackup -> R.string.borrow_backup
    StationTarget.ReturnBackup -> R.string.return_backup
})

@Composable
private fun failureText(reason: FailureReason): String = stringResource(when (reason) {
    FailureReason.NotConfigured -> R.string.error_not_configured
    FailureReason.Network -> R.string.error_network
    FailureReason.Service -> R.string.error_service
    FailureReason.InvalidResponse -> R.string.error_response
})
