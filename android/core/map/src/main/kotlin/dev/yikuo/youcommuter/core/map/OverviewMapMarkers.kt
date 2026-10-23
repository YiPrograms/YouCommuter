package dev.yikuo.youcommuter.core.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberUpdatedMarkerState

/** Saved stations retain their actual positions and individual count markers. */
@Composable
@GoogleMapComposable
internal fun OverviewMapMarkers(
    points: List<StationMapPoint>,
    onStationSelected: (StationMapPoint) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val icons = remember(context, density, colors) {
        StationMarkerIcons(context, density.density, density.fontScale, colors.surface.toArgb(),
            colors.onSurface.toArgb(), colors.primary.toArgb(), colors.tertiary.toArgb())
    }
    for (point in points) key(point.key) {
        Marker(state = rememberUpdatedMarkerState(point.coordinate), title = point.name, contentDescription = point.name,
            snippet = pointDescription(point), icon = icons.icon(point, selected = point.selected), anchor = Offset(.5f, .5f),
            zIndex = if (point.selected) 10f else 5f,
            onClick = { if (point.selectable) onStationSelected(point); true })
    }
}
