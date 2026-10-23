package dev.yikuo.youcommuter.core.map

import android.content.Context
import android.content.pm.PackageManager
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdate
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.rememberCameraPositionState
import dev.yikuo.youcommuter.core.ui.AppIcon
import dev.yikuo.youcommuter.core.ui.AppIconButton
import dev.yikuo.youcommuter.core.ui.AppIconType
import dev.yikuo.youcommuter.core.ui.IconText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MapViewToggle(showMap: Boolean, onToggle: () -> Unit) {
    AppIconButton(if (showMap) AppIconType.List else AppIconType.Map,
        stringResource(if (showMap) R.string.list_view else R.string.map_view), onToggle)
}

@Composable
fun MapMetricSelector(metric: MapMetric, onSelected: (MapMetric) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (value in MapMetric.entries) FilterChip(selected = metric == value, onClick = { onSelected(value) },
            leadingIcon = { AppIcon(if (value == MapMetric.Bikes) AppIconType.Bike else AppIconType.Dock) },
            label = { Text(stringResource(if (value == MapMetric.Bikes) R.string.map_bikes else R.string.map_docks)) })
    }
}

/** Standard native Google Maps with foreground location; no Map ID or road-routing API. */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
fun StationMap(
    points: List<StationMapPoint>,
    onStationSelected: (StationMapPoint) -> Unit,
    modifier: Modifier = Modifier,
    cameraKey: String = "stations",
    selectedStationId: String? = null,
    initialFocus: MapFocus? = null,
    mode: StationMapMode = StationMapMode.Browse,
) {
    val validPoints = remember(points) { points.filter { it.hasPosition }.distinctBy { it.key } }
    val context = LocalContext.current
    val configured = remember(context) { hasGoogleMapsKey(context) }
    if (validPoints.isEmpty() || !configured) {
        Box(modifier, contentAlignment = Alignment.Center) {
            IconText(if (configured) AppIconType.Location else AppIconType.Warning,
                stringResource(if (configured) R.string.map_no_positions else R.string.map_not_configured))
        }
        return
    }
    val camera = rememberCameraPositionState {
        position = initialFocus?.let { CameraPosition.fromLatLngZoom(LatLng(it.latitude, it.longitude), it.zoom) }
            ?: CameraPosition.fromLatLngZoom(validPoints.first().coordinate, 14f)
    }
    var positionedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val padding = minOf(with(density) { 48.dp.roundToPx() }, minOf(size.width, size.height) / 4)
    val scope = rememberCoroutineScope()
    val interactive = mode == StationMapMode.Browse
    val location = rememberMapLocation { coordinate -> scope.launch { camera.animate(CameraUpdateFactory.newLatLngZoom(coordinate, 16f)) } }
    val fitPoints = validPoints.filter { it.selectable }.ifEmpty { validPoints }
    val selected = validPoints.filter { it.selected || it.stationId == selectedStationId }
    val selectedKeys = selected.map { it.key }
    var previousSelected by remember(cameraKey) { mutableStateOf(selectedKeys) }
    LaunchedEffect(loaded, cameraKey, size) {
        if (loaded && size.width > 0 && size.height > 0 && positionedKey != cameraKey) {
            camera.move(initialFocus?.let { CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), it.zoom) }
                ?: fitCamera(fitPoints.map { it.coordinate }, padding, camera.position.zoom))
            positionedKey = cameraKey
        }
    }
    LaunchedEffect(loaded, selectedKeys) {
        if (interactive && loaded && previousSelected != selectedKeys && selected.size == 1) {
            camera.animate(CameraUpdateFactory.newLatLng(selected.single().coordinate))
        }
        previousSelected = selectedKeys
    }
    LaunchedEffect(retry, loaded) {
        if (!loaded) {
            delay(15_000)
            failed = true
        }
    }
    val actions = if (validPoints.size <= 20) validPoints.filter { it.selectable }.map { point ->
        val label = pointDescription(point)
        CustomAccessibilityAction(label) { onStationSelected(point); true }
    } else emptyList()
    Box(modifier.onSizeChanged { size = it }) {
        key(retry) {
            GoogleMap(
                modifier = Modifier.fillMaxSize().semantics { customActions = actions },
                cameraPositionState = camera,
                contentDescription = stringResource(R.string.map_view),
                contentPadding = if (interactive) PaddingValues(end = 64.dp) else PaddingValues(0.dp),
                properties = MapProperties(maxZoomPreference = 19f, isMyLocationEnabled = location.permitted),
                uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false,
                    mapToolbarEnabled = false, rotationGesturesEnabled = false, tiltGesturesEnabled = false,
                    scrollGesturesEnabled = interactive, zoomGesturesEnabled = interactive),
                mapColorScheme = ComposeMapColorScheme.FOLLOW_SYSTEM,
                onMapLoaded = { loaded = true; failed = false },
                // The SDK owns MapView lifecycle, attribution and marker accessibility.
                mapViewFactory = { mapContext, options ->
                    MapView(mapContext, options).apply {
                        setOnTouchListener { _, event ->
                            parent?.requestDisallowInterceptTouchEvent(interactive && event.actionMasked != MotionEvent.ACTION_UP &&
                                event.actionMasked != MotionEvent.ACTION_CANCEL)
                            false
                        }
                    }
                },
            ) {
                if (interactive) StationMarkers(validPoints, selected, onStationSelected, onClusterSelected = { coordinates ->
                    scope.launch { camera.animate(fitCamera(coordinates, padding, camera.position.zoom)) }
                }) else OverviewMapMarkers(validPoints, onStationSelected)
            }
        }
        if (interactive) Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .95f)) {
            Column {
                AppIconButton(AppIconType.FitMap, stringResource(R.string.fit_stations), {
                    scope.launch { camera.animate(fitCamera(fitPoints.map { it.coordinate }, padding, camera.position.zoom)) }
                }, enabled = loaded)
                AppIconButton(AppIconType.MyLocation, stringResource(R.string.my_location), location.locate,
                    enabled = loaded && !location.locating)
                AppIconButton(AppIconType.Add, stringResource(R.string.zoom_in), {
                    scope.launch { camera.animate(CameraUpdateFactory.zoomIn()) }
                }, enabled = loaded)
                AppIconButton(AppIconType.Remove, stringResource(R.string.zoom_out), {
                    scope.launch { camera.animate(CameraUpdateFactory.zoomOut()) }
                }, enabled = loaded)
            }
        }
        if (!loaded && !failed) Surface(Modifier.align(Alignment.TopStart).padding(12.dp), shape = RoundedCornerShape(12.dp)) {
            CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp), strokeWidth = 2.dp)
        }
        if (failed) Surface(Modifier.align(Alignment.TopStart).padding(8.dp), shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconText(AppIconType.Warning, stringResource(R.string.map_unavailable), style = MaterialTheme.typography.bodySmall)
                AppIconButton(AppIconType.Refresh, stringResource(R.string.map_retry), { loaded = false; failed = false; retry++ })
            }
        }
    }
}

@Composable
internal fun pointDescription(point: StationMapPoint): String = when (point.metric) {
    null -> point.name
    else -> stringResource(if (point.metric == MapMetric.Bikes) R.string.map_station_bikes else R.string.map_station_docks,
        point.name, point.count?.toString() ?: "—")
}

@Suppress("DEPRECATION")
private fun hasGoogleMapsKey(context: Context): Boolean =
    !context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        .metaData?.getString("com.google.android.geo.API_KEY").isNullOrBlank()

internal val StationMapPoint.coordinate: LatLng get() = LatLng(latitude, longitude)

private fun fitCamera(coordinates: List<LatLng>, padding: Int, currentZoom: Float): CameraUpdate {
    val unique = coordinates.distinct()
    return if (unique.size == 1) CameraUpdateFactory.newLatLngZoom(unique.single(), (currentZoom + 2).coerceIn(16f, 19f))
    else CameraUpdateFactory.newLatLngBounds(LatLngBounds.builder().apply { unique.forEach(::include) }.build(), padding)
}
