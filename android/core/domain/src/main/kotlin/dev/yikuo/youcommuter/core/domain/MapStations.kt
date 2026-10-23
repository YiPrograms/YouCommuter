package dev.yikuo.youcommuter.core.domain

import dev.yikuo.youcommuter.core.model.BikeStation
import dev.yikuo.youcommuter.core.model.FailureReason
import dev.yikuo.youcommuter.core.model.StationResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Maps must not silently stop at the first alphabetic page of a region. */
suspend fun StationRepository.mapStations(region: String, query: String): StationResult<List<BikeStation>> {
    val stations = linkedMapOf<String, BikeStation>()
    val cursors = mutableSetOf<String>()
    var cursor: String? = null
    do {
        val result = search(region, query.trim(), cursor, limit = 200)
        currentCoroutineContext().ensureActive()
        when (result) {
            is StationResult.Failure -> return result
            is StationResult.Success -> {
                result.value.stations.forEach { stations[it.id.value] = it }
                cursor = result.value.nextCursor
                if (cursor != null && !cursors.add(cursor)) return StationResult.Failure(FailureReason.InvalidResponse)
            }
        }
    } while (cursor != null)
    return StationResult.Success(stations.values.toList())
}
