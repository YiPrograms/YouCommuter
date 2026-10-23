package dev.yikuo.youcommuter.core.domain

import dev.yikuo.youcommuter.core.model.CommuteRoute
import dev.yikuo.youcommuter.core.model.CommuteSession
import dev.yikuo.youcommuter.core.model.CoverageResult
import dev.yikuo.youcommuter.core.model.BikeStation
import dev.yikuo.youcommuter.core.model.StationPage
import dev.yikuo.youcommuter.core.model.StationLocation
import dev.yikuo.youcommuter.core.model.StationResult
import dev.yikuo.youcommuter.core.model.AvailabilityForecast
import dev.yikuo.youcommuter.core.model.ForecastRequest
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import java.time.Instant

interface CoverageRepository {
    suspend fun getCoverage(): CoverageResult
}

interface StationRepository {
    /** All matching locations in one read; selection does not need live counts or pagination. */
    suspend fun locations(region: String, query: String): StationResult<List<StationLocation>>
    suspend fun location(id: String): StationResult<StationLocation>
    /** Read one accepted snapshot for the requested stations. Missing IDs are omitted. */
    suspend fun getMany(ids: Set<String>): StationResult<List<BikeStation>>
    /** Fetch current inventory once for an explicit refresh; subsequent reads share that snapshot. */
    suspend fun refresh(): StationResult<Unit>
    suspend fun search(region: String, query: String, cursor: String? = null, limit: Int = 50): StationResult<StationPage>
    suspend fun get(id: String): StationResult<BikeStation>
}

interface ForecastRepository {
    suspend fun forecasts(requests: List<ForecastRequest>): StationResult<List<AvailabilityForecast>>
}

interface RouteRepository {
    fun observeRoutes(): Flow<List<CommuteRoute>>
    suspend fun save(route: CommuteRoute)
    suspend fun delete(id: UUID)
}

interface CommuteSessionRepository {
    fun observeSession(): Flow<CommuteSession?>
    suspend fun start(route: CommuteRoute, at: Instant): CommuteSession
    suspend fun dispatch(sessionId: UUID, command: CommuteCommand): CommuteSession
    suspend fun clearTerminal()
}

interface ReminderScheduler {
    suspend fun reconcile(routes: List<CommuteRoute>)
}
