package dev.yikuo.youcommuter.core.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import dev.yikuo.youcommuter.core.model.BikeKind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker as NativeMarker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.maps.android.clustering.ClusterItem
import com.google.maps.android.clustering.ClusterManager
import com.google.maps.android.clustering.view.DefaultClusterRenderer
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.clustering.Clustering
import com.google.maps.android.compose.clustering.rememberClusterManager
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlin.math.ceil
import dev.yikuo.youcommuter.core.ui.R as UiR

/** Native clustering never schedules Compose views after their map host has been detached. */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
@GoogleMapComposable
internal fun StationMarkers(
    points: List<StationMapPoint>,
    selected: List<StationMapPoint>,
    onStationSelected: (StationMapPoint) -> Unit,
    onClusterSelected: (List<LatLng>) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val icons = remember(context, density, colors) {
        StationMarkerIcons(context, density.density, density.fontScale, colors.surface.toArgb(),
            colors.onSurface.toArgb(), colors.primary.toArgb(), colors.tertiary.toArgb())
    }
    val pointsByKey by rememberUpdatedState(points.associateBy { it.key })
    val geometry = points.filter { it.selectable && it.role == MapPointRole.Station }
        .map { StationClusterItem(it.key, it.name, it.coordinate) }
    val items = remember(geometry) { geometry }
    val manager = rememberClusterManager<StationClusterItem>()
    var renderer by remember { mutableStateOf<StationClusterRenderer?>(null) }
    MapEffect(manager, icons) { map ->
        renderer = manager?.let { StationClusterRenderer(context, map, it, icons) { pointsByKey } }
    }
    SideEffect {
        val currentManager = manager ?: return@SideEffect
        val currentRenderer = renderer ?: return@SideEffect
        if (currentManager.renderer !== currentRenderer) currentManager.renderer = currentRenderer
        currentManager.setOnClusterClickListener { onClusterSelected(it.items.map { item -> item.position }); true }
        currentManager.setOnClusterItemClickListener { item ->
            pointsByKey[item.key]?.takeIf { it.selectable }?.let(onStationSelected); true
        }
        currentRenderer.updateVisible(items)
    }
    if (manager != null && renderer != null) Clustering(items, manager)
    val overlays = (selected + points.filter { it.role == MapPointRole.Primary }).distinctBy { it.key }
    for (point in overlays) key(point.key) {
        Marker(state = rememberUpdatedMarkerState(point.coordinate), title = point.name,
            contentDescription = if (point.role == MapPointRole.Primary)
                context.getString(R.string.map_primary_station, point.name) else point.name,
            snippet = point.inventory?.let { pointDescription(point) }, icon = icons.icon(point, selected = true),
            anchor = if (point.inventory == null) Offset(.5f, 1f) else Offset(.5f, .5f), zIndex = 10f,
            onClick = { if (point.selectable) onStationSelected(point); true })
    }
}

/** Geometry is independent from live counts: polling updates icons without clearing clusters. */
private data class StationClusterItem(val key: String, val name: String, val coordinate: LatLng) : ClusterItem {
    override fun getPosition(): LatLng = coordinate
    override fun getTitle(): String = name
    override fun getSnippet(): String? = null
    override fun getZIndex(): Float = 0f
}

private class StationClusterRenderer(
    context: Context,
    map: GoogleMap,
    manager: ClusterManager<StationClusterItem>,
    private val icons: StationMarkerIcons,
    private val points: () -> Map<String, StationMapPoint>,
) : DefaultClusterRenderer<StationClusterItem>(context, map, manager) {
    private val renderedInventory = mutableMapOf<String, MapInventory?>()
    init { setAnimation(false) }

    override fun onBeforeClusterItemRendered(item: StationClusterItem, options: MarkerOptions) {
        super.onBeforeClusterItemRendered(item, options)
        val point = points()[item.key] ?: return
        options.icon(icons.icon(point)).anchor(.5f, if (point.inventory == null) 1f else .5f)
            .contentDescription(point.name)
        renderedInventory[item.key] = point.inventory
    }

    override fun onClusterItemUpdated(item: StationClusterItem, marker: NativeMarker) {
        super.onClusterItemUpdated(item, marker)
        points()[item.key]?.let { marker.setIcon(icons.icon(it)); renderedInventory[item.key] = it.inventory }
    }

    fun updateVisible(items: List<StationClusterItem>) {
        renderedInventory.keys.retainAll(items.mapTo(hashSetOf()) { it.key })
        for (item in items) {
            val point = points()[item.key] ?: continue
            if (renderedInventory.containsKey(item.key) && renderedInventory[item.key] == point.inventory) continue
            getMarker(item)?.setIcon(icons.icon(point))
            renderedInventory[item.key] = point.inventory
        }
    }
}

/** Share small inventory bitmaps by value instead of composing a view for every station. */
internal class StationMarkerIcons(
    private val context: Context,
    private val scale: Float,
    fontScale: Float,
    private val background: Int,
    text: Int,
    private val bikes: Int,
    private val docks: Int,
) {
    private data class IconKey(val inventory: MapInventory?, val selected: Boolean, val primary: Boolean)
    private val cache = LruCache<IconKey, BitmapDescriptor>(128)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = text
        textSize = 14 * scale * fontScale
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun icon(point: StationMapPoint, selected: Boolean = false): BitmapDescriptor {
        val key = IconKey(point.inventory, selected, point.role == MapPointRole.Primary)
        return cache.get(key) ?: create(key).also { cache.put(key, it) }
    }

    private fun create(key: IconKey): BitmapDescriptor {
        val inventory = key.inventory ?: return BitmapDescriptorFactory.defaultMarker(
            when { key.primary -> BitmapDescriptorFactory.HUE_VIOLET; key.selected -> BitmapDescriptorFactory.HUE_AZURE; else -> BitmapDescriptorFactory.HUE_RED })
        val label = inventory.count?.toString() ?: "—"
        val color = if (inventory.metric == MapMetric.Bikes) bikes else docks
        val iconSize = 18 * scale
        val inset = 10 * scale
        val gap = 6 * scale
        val width = ceil(maxOf(58 * scale, inset * 2 + iconSize + gap + textPaint.measureText(label))).toInt()
        val metrics = textPaint.fontMetrics
        val height = ceil(maxOf(34 * scale, metrics.descent - metrics.ascent + 14 * scale)).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val border = (if (key.selected) 3 else 1) * scale
        val edge = border / 2
        paint.style = Paint.Style.FILL
        paint.color = background
        canvas.drawRoundRect(edge, edge, width - edge, height - edge, 12 * scale, 12 * scale, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = border
        paint.color = color
        canvas.drawRoundRect(edge, edge, width - edge, height - edge, 12 * scale, 12 * scale, paint)
        val resource = when {
            inventory.metric == MapMetric.Docks -> UiR.drawable.ic_dock
            inventory.bikeKind == BikeKind.Electric -> UiR.drawable.ic_bolt
            else -> UiR.drawable.ic_bike
        }
        requireNotNull(context.getDrawable(resource)).mutate().apply {
            setTint(color)
            val top = ((height - iconSize) / 2).toInt()
            setBounds(inset.toInt(), top, (inset + iconSize).toInt(), top + iconSize.toInt())
            draw(canvas)
        }
        canvas.drawText(label, inset + iconSize + gap, (height - metrics.ascent - metrics.descent) / 2, textPaint)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}
