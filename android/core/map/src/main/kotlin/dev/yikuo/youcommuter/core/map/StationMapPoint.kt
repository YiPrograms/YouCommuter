package dev.yikuo.youcommuter.core.map

import dev.yikuo.youcommuter.core.model.BikeStation
import dev.yikuo.youcommuter.core.model.Freshness
import dev.yikuo.youcommuter.core.model.StationServiceStatus
import dev.yikuo.youcommuter.core.model.StationLocation
import dev.yikuo.youcommuter.core.model.BikeKind

enum class MapMetric { Bikes, Docks }

data class MapInventory(val metric: MapMetric, val count: Int?, val bikeKind: BikeKind = BikeKind.All)
enum class MapPointRole { Station, Primary }
enum class StationMapMode { Browse, Overview }
data class MapFocus(val latitude: Double, val longitude: Double, val zoom: Float = 16f)

data class StationMapPoint(
    val stationId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val inventory: MapInventory? = null,
    val selected: Boolean = false,
    val selectable: Boolean = true,
    val role: MapPointRole = MapPointRole.Station,
) {
    val metric: MapMetric? get() = inventory?.metric
    val count: Int? get() = inventory?.count
    val key: String get() = stationId
    val hasPosition: Boolean get() = latitude.isFinite() && longitude.isFinite() &&
        latitude in -85.05112878..85.05112878 && longitude in -180.0..180.0
}

fun BikeStation.mapPoint(metric: MapMetric, selected: Boolean = false, bikeKind: BikeKind = BikeKind.All): StationMapPoint {
    val usable = observation?.takeIf { it.freshness == Freshness.Fresh && it.serviceStatus == StationServiceStatus.Operating }
    return StationMapPoint(id.value, name, latitude, longitude, MapInventory(metric,
        when (metric) {
            MapMetric.Bikes -> usable?.counts?.let(bikeKind::count)
            MapMetric.Docks -> usable?.counts?.returnDocks
        }, bikeKind), selected)
}

fun StationLocation.mapPoint() = StationMapPoint(id.value, name, latitude, longitude)
