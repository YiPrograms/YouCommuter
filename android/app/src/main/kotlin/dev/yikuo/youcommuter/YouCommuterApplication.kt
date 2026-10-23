package dev.yikuo.youcommuter

import android.app.Application
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.yikuo.youcommuter.core.data.YouCommuterRepositories
import dev.yikuo.youcommuter.core.data.RepositorySet
import dev.yikuo.youcommuter.core.database.YouCommuterDatabase
import dev.yikuo.youcommuter.core.database.LocalRepositorySet
import dev.yikuo.youcommuter.core.domain.CommuteSessionRepository
import dev.yikuo.youcommuter.core.domain.CoverageRepository
import dev.yikuo.youcommuter.core.domain.RouteRepository
import dev.yikuo.youcommuter.core.domain.StationRepository
import dev.yikuo.youcommuter.core.platform.AndroidReminderScheduler
import dev.yikuo.youcommuter.core.platform.ReminderDataProvider
import dev.yikuo.youcommuter.core.platform.BorrowDetectionDataProvider
import dev.yikuo.youcommuter.core.model.BikeStation
import dev.yikuo.youcommuter.core.model.StationResult
import dev.yikuo.youcommuter.core.model.AvailabilityForecast
import dev.yikuo.youcommuter.core.model.ForecastRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import dev.yikuo.youcommuter.core.model.CommuteRoute
import java.util.concurrent.TimeUnit

class YouCommuterApplication : Application(), ReminderDataProvider, BorrowDetectionDataProvider {
    internal val predictionSettings by lazy { PredictionPreferences(this) }
    private val remoteRepositories: RepositorySet by lazy {
        YouCommuterRepositories.create(predictionSettings)
    }
    private val localRepositories by lazy { YouCommuterDatabase.create(this) }
    val container: AppContainer by lazy { AppContainer(this, remoteRepositories, localRepositories) }
    override val sessionsForBorrowDetection: CommuteSessionRepository get() = localRepositories.sessions

    override fun onCreate() {
        super.onCreate()
        val request = PeriodicWorkRequestBuilder<ReminderRefreshWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "youcommuter-reminder-horizon-refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    override fun withForecastConfiguration(revision: Long, action: () -> Unit) = predictionSettings.withRevision(revision, action)
    override val predictionsEnabled: Boolean get() = predictionSettings.load().endpoint.isNotBlank()
    override val forecastConfigurationRevision: Long get() = predictionSettings.revision

    override suspend fun routeForReminder(id: UUID): CommuteRoute? =
        localRepositories.routes.observeRoutes().first().firstOrNull { it.id == id }

    override suspend fun stationForReminder(id: String): BikeStation? {
        if (id.isBlank()) return null
        return when (val result = remoteRepositories.stations.get(id)) {
            is StationResult.Success -> result.value
            is StationResult.Failure -> null
        }
    }

    override suspend fun forecastsForReminder(requests: List<ForecastRequest>): List<AvailabilityForecast> =
        when (val result = remoteRepositories.forecasts.forecasts(requests)) {
            is StationResult.Success -> result.value
            is StationResult.Failure -> emptyList()
        }
}

/** The composition root owns adapter construction; feature code only receives ports. */
class AppContainer(context: Context, repositories: RepositorySet, localRepositories: LocalRepositorySet) {
    val testPrediction = repositories.testPrediction
    val coverage: CoverageRepository = repositories.coverage
    val stations: StationRepository = repositories.stations
    val routes: RouteRepository = localRepositories.routes
    val sessions: CommuteSessionRepository = localRepositories.sessions
    private val reminderScheduler = AndroidReminderScheduler(context)
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        applicationScope.launch {
            routes.observeRoutes().collect { savedRoutes ->
                runCatching { reminderScheduler.reconcile(savedRoutes) }
            }
        }
    }

    suspend fun refreshReminderSchedule() {
        reminderScheduler.reconcile(routes.observeRoutes().first())
    }
}

class ReminderRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        (applicationContext as YouCommuterApplication).container.refreshReminderSchedule()
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { Result.retry() },
    )
}
