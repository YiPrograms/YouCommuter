package dev.yikuo.youcommuter.feature.commute

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yikuo.youcommuter.core.domain.*
import dev.yikuo.youcommuter.core.model.*
import dev.yikuo.youcommuter.core.ui.AppIcon
import dev.yikuo.youcommuter.core.ui.AppIconType
import dev.yikuo.youcommuter.core.ui.IconText

@Composable
internal fun CommuteStationGroup(
    stations: List<RouteStation>, selected: RouteStation, state: CommuteUiState,
    route: CommuteRoute, borrowing: Boolean, enabled: Boolean, onSelected: (RouteStation) -> Unit,
) {
    val counts = stations.associate { ref -> ref.id to state.stationForCounts(ref.id)?.usableCounts?.let {
        if (borrowing) route.thresholdBikeKind.count(it) else it.returnDocks
    } }
    val threshold = if (borrowing) route.lowBikeThreshold else route.lowDockThreshold
    val recommended = recommendedStation(stations, counts, threshold)
    val byId = state.mapStations.associateBy { it.id }
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconText(if (borrowing) AppIconType.Bike else AppIconType.Dock,
                stringResource(if (borrowing) R.string.borrow_group else R.string.return_group), Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge)
            if (state.inventoryFailure != null || state.unavailableStationIds.any { id -> stations.any { it.id == id } }) {
                AppIcon(AppIconType.Warning, contentDescription = stringResource(R.string.availability_network_error),
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
        }
        stations.forEach { ref ->
            val level = availabilityLevel(counts[ref.id], threshold)
            val emphasized = ref.id == recommended
            val raw = byId[ref.id]?.observation?.counts
            StationCountRow(ref, ref.id == selected.id, emphasized, level,
                if (borrowing) route.bikeDisplay else null, raw, enabled) { onSelected(ref) }
        }
    }
}

@Composable
private fun StationCountRow(
    station: RouteStation, selected: Boolean, emphasized: Boolean, level: AvailabilityLevel,
    bikeKind: BikeKind?, counts: AvailabilityCounts?, enabled: Boolean, onClick: () -> Unit,
) {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val countColumns = if (bikeKind == BikeKind.All) 2 else 1
    val countWidth = (60 * countColumns * scale + if (countColumns > 1) 16 else 0).dp
    val color = availabilityColor(level)
    Surface(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.small,
        color = if (emphasized) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            val stacked = maxWidth < countWidth + 124.dp
            Row(Modifier.fillMaxWidth().height((if (stacked) 72 else 52).dp * scale),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    if (selected) AppIcon(AppIconType.Check, Modifier.size(18.dp))
                }
                if (stacked) Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    StationName(station, emphasized, Modifier.fillMaxWidth())
                    CountValues(counts, bikeKind, emphasized, color, countWidth)
                } else {
                    StationName(station, emphasized, Modifier.weight(1f))
                    CountValues(counts, bikeKind, emphasized, color, countWidth)
                }
            }
        }
    }
}

@Composable
private fun StationName(station: RouteStation, emphasized: Boolean, modifier: Modifier) {
    Text(station.name, modifier, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = if (emphasized) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
        fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal)
}

@Composable
private fun CountValues(counts: AvailabilityCounts?, kind: BikeKind?, emphasized: Boolean, color: Color, width: androidx.compose.ui.unit.Dp) {
    Row(Modifier.width(width), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        when (kind) {
            null -> CountValue(AppIconType.Dock, counts?.returnDocks, R.string.available_docks, emphasized, color, Modifier.weight(1f))
            BikeKind.All -> {
                CountValue(AppIconType.Bike, counts?.regularBikes, R.string.regular_bikes, emphasized, color, Modifier.weight(1f))
                CountValue(AppIconType.Bolt, counts?.electricBikes, R.string.electric_bikes, emphasized, color, Modifier.weight(1f))
            }
            BikeKind.Regular -> CountValue(AppIconType.Bike, counts?.regularBikes, R.string.regular_bikes, emphasized, color, Modifier.weight(1f))
            BikeKind.Electric -> CountValue(AppIconType.Bolt, counts?.electricBikes, R.string.electric_bikes, emphasized, color, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CountValue(icon: AppIconType, count: Int?, description: Int, emphasized: Boolean, color: Color, modifier: Modifier) {
    val value = count?.toString() ?: "—"
    val label = stringResource(description, value)
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
        AppIcon(icon, Modifier.size(15.dp), tint = color)
        Text(value, textAlign = TextAlign.End, color = color, maxLines = 1,
            style = (if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge)
                .copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun availabilityColor(level: AvailabilityLevel): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return when (level) {
        AvailabilityLevel.Available -> if (dark) Color(0xFF8DDA9D) else Color(0xFF146C2E)
        AvailabilityLevel.Low -> if (dark) Color(0xFFFFC47B) else Color(0xFF8A4B00)
        AvailabilityLevel.Empty -> MaterialTheme.colorScheme.error
        AvailabilityLevel.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
