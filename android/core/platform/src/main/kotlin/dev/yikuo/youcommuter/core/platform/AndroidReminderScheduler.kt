package dev.yikuo.youcommuter.core.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.yikuo.youcommuter.core.domain.ReminderScheduler
import dev.yikuo.youcommuter.core.domain.departureReminderExpiry
import dev.yikuo.youcommuter.core.domain.inventoryReminderExpiry
import dev.yikuo.youcommuter.core.domain.upcomingReminderOccurrences
import dev.yikuo.youcommuter.core.domain.usableReminderForecast
import dev.yikuo.youcommuter.core.domain.estimatedReturnAt
import dev.yikuo.youcommuter.core.model.ArrivalDistribution
import dev.yikuo.youcommuter.core.model.ArrivalPoint
import dev.yikuo.youcommuter.core.model.ArrivalSource
import dev.yikuo.youcommuter.core.model.AvailabilityForecast
import dev.yikuo.youcommuter.core.model.AvailabilityMetric
import dev.yikuo.youcommuter.core.model.BikeKind
import dev.yikuo.youcommuter.core.model.forecastMetric
import dev.yikuo.youcommuter.core.model.BikeStation
import dev.yikuo.youcommuter.core.model.CommuteRoute
import dev.yikuo.youcommuter.core.model.Freshness
import dev.yikuo.youcommuter.core.model.ForecastRequest
import dev.yikuo.youcommuter.core.model.ForecastStatus
import dev.yikuo.youcommuter.core.model.StationServiceStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import dev.yikuo.youcommuter.core.model.RouteStation
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AndroidReminderScheduler(
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
) : ReminderScheduler {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val preferences = appContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    private val reconcileMutex = Mutex()

    init { DepartureReminderWorker.createChannel(appContext) }

    override suspend fun reconcile(routes: List<CommuteRoute>) = reconcileMutex.withLock {
        withContext(Dispatchers.IO) {
            val previousIds = preferences.getStringSet(ScheduledRouteIds, emptySet()).orEmpty()
            val currentIds = routes.mapTo(mutableSetOf()) { it.id.toString() }
            (previousIds + currentIds).forEach { id ->
                workManager.cancelAllWorkByTag(routeTag(id)).result.get()
            }

            routes.forEach { route ->
                upcomingReminderOccurrences(route, clock.instant()).forEach { occurrence ->
                    val delayMillis = Duration.between(clock.instant(), occurrence.reminderAt).toMillis().coerceAtLeast(0)
                    val input = Data.Builder()
                        .putString(ReminderData.RouteId, route.id.toString())
                        .putLong(ReminderData.RouteRevision, route.revision)
                        .putLong(ReminderData.DepartureEpochMillis, occurrence.departureAt.toEpochMilli())
                        .build()
                    val request = OneTimeWorkRequestBuilder<DepartureReminderWorker>()
                        .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                        .setInputData(input)
                        .addTag(GlobalTag)
                        .addTag(routeTag(route.id.toString()))
                        .build()
                    val workName = "youcommuter-reminder:${route.id}:${occurrence.localDate}"
                    workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request).result.get()
                }
            }
            preferences.edit { putStringSet(ScheduledRouteIds, currentIds) }
        }
    }

    private fun routeTag(routeId: String) = "youcommuter-reminder-route:$routeId"

    private companion object {
        const val PreferencesName = "youcommuter_reminders"
        const val ScheduledRouteIds = "scheduled_route_ids"
        const val GlobalTag = "youcommuter-departure-reminder"
    }
}

/** Supplies observations and forecasts without coupling the worker to the networking adapter. */
interface ReminderDataProvider {
    val forecastConfigurationRevision: Long
    val predictionsEnabled: Boolean
    fun withForecastConfiguration(revision: Long, action: () -> Unit): Boolean
    suspend fun routeForReminder(id: UUID): CommuteRoute?
    suspend fun stationForReminder(id: String): BikeStation?
    suspend fun forecastsForReminder(requests: List<ForecastRequest>): List<AvailabilityForecast>
}

class DepartureReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val departureEpoch = inputData.getLong(ReminderData.DepartureEpochMillis, 0)
        val routeRevision = inputData.getLong(ReminderData.RouteRevision, 0)
        val routeId = inputData.getString(ReminderData.RouteId) ?: return Result.failure()
        if (departureEpoch <= 0 || routeRevision <= 0) return Result.failure()
        if (System.currentTimeMillis() > departureEpoch + ExpiryAfterDepartureMillis) return Result.success()
        if (!NotificationCapability.read(applicationContext).canPost) return Result.success()
        createChannel(applicationContext)

        val provider = applicationContext as ReminderDataProvider
        val route = provider.routeForReminder(UUID.fromString(routeId)) ?: return Result.success()
        if (route.revision != routeRevision) return Result.success()
        val departure = Instant.ofEpochMilli(departureEpoch)
        val occurrenceId = "${route.id}:${departure.atZone(ZoneId.of("Asia/Taipei")).toLocalDate()}"
        val routeName = route.name
        val borrowStationName = route.borrow.primary.name
        val returnStationName = route.returns.primary.name
        val departureTime = route.departure.toString()
        val bikeDisplay = route.bikeDisplay
        val bikeThreshold = route.thresholdBikeKind
        val borrowMetric = bikeThreshold.forecastMetric
        val lowBikeThreshold = route.lowBikeThreshold
        val lowDockThreshold = route.lowDockThreshold
        val borrowPrimary = route.borrow.primary.reminderRef()
        val returnPrimary = route.returns.primary.reminderRef()
        val borrowBackups = route.borrow.backups.map(RouteStation::reminderRef)
        val returnBackups = route.returns.backups.map(RouteStation::reminderRef)
        val configurationRevision = provider.forecastConfigurationRevision
        val primaryStations = provider.stationsForReminder(listOf(borrowPrimary, returnPrimary))
        val borrowStation = primaryStations[borrowPrimary.id]
        val returnStation = primaryStations[returnPrimary.id]
        val backupRefs = buildList {
            if (needsBorrowBackup(borrowStation, lowBikeThreshold, bikeThreshold)) addAll(borrowBackups)
            if (needsReturnBackup(returnStation, lowDockThreshold)) addAll(returnBackups)
        }
        val backupStations = provider.stationsForReminder(backupRefs)
        val borrowBackup = if (needsBorrowBackup(borrowStation, lowBikeThreshold, bikeThreshold)) {
            selectBackup(borrowBackups, backupStations, lowBikeThreshold) { it.availableBikes(bikeThreshold) }
        } else {
            null
        }
        val returnBackup = if (needsReturnBackup(returnStation, lowDockThreshold)) {
            selectBackup(returnBackups, backupStations, lowDockThreshold) { it.availableDocks() }
        } else {
            null
        }
        val returnAt = estimatedReturnAt(departure, route.estimatedReturnMinutes)
        val requests = buildList {
            if (borrowStation != null && departure > Instant.now()) {
                add(reminderForecastRequest(borrowStation, borrowMetric, departure, lowBikeThreshold))
            }
            if (returnStation != null && returnAt != null && returnAt > Instant.now()) {
                add(reminderForecastRequest(returnStation, AvailabilityMetric.ReturnDocks, returnAt, lowDockThreshold))
            }
        }
        val forecasts = if (provider.predictionsEnabled && requests.isNotEmpty()) {
            withTimeoutOrNull(6_000) {
                provider.forecastsForReminder(requests)
            }.orEmpty()
        } else {
            emptyList()
        }
        val borrowForecast = usableReminderForecast(
            forecasts.firstOrNull { it.stationId == borrowStation?.id && it.metric == borrowMetric },
            borrowStation, departure, Instant.now(), borrowMetric,
        )
        val returnForecast = returnAt?.let { arrival ->
            usableReminderForecast(
                forecasts.firstOrNull { it.stationId == returnStation?.id && it.metric == AvailabilityMetric.ReturnDocks },
                returnStation, arrival, Instant.now(), AvailabilityMetric.ReturnDocks,
            )
        }
        val expiresAt = inventoryReminderExpiry(departureReminderExpiry(departure, borrowForecast, returnForecast),
            listOfNotNull(borrowStation?.observation, returnStation?.observation), Instant.now())
        val content = reminderContent(
            context = applicationContext,
            routeName = routeName,
            departureTime = departureTime,
            borrowStationName = borrowStationName,
            returnStationName = returnStationName,
            borrowStation = borrowStation,
            returnStation = returnStation,
            borrowBackup = borrowBackup,
            returnBackup = returnBackup,
            bikeDisplay = bikeDisplay, bikeThreshold = bikeThreshold,
            lowBikeThreshold = lowBikeThreshold,
            lowDockThreshold = lowDockThreshold,
            borrowForecast = borrowForecast,
            returnForecast = returnForecast,
            returnTime = returnAt?.let { reminderTime(applicationContext, it) },
            showForecast = provider.predictionsEnabled,
        )
        val launchIntent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                applicationContext,
                routeId.hashCode(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val compactView = reminderCompactView(applicationContext, content)
        val expandedView = reminderExpandedView(applicationContext, content)
        val notification = NotificationCompat.Builder(applicationContext, ChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.summary)
            .setCustomContentView(compactView)
            .setCustomBigContentView(expandedView)
            .setCustomHeadsUpContentView(compactView)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setColor(applicationContext.getColor(R.color.notification_accent))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(Duration.between(Instant.now(), expiresAt).toMillis().coerceAtLeast(1))
            .setContentIntent(contentIntent)
            .build()

        if (provider.routeForReminder(route.id)?.revision != routeRevision) return Result.success()
        if (configurationRevision != provider.forecastConfigurationRevision) return Result.retry()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            val posted = provider.withForecastConfiguration(configurationRevision) {
                NotificationManagerCompat.from(applicationContext).notify(occurrenceId, NotificationId, notification)
            }
            if (!posted) return Result.retry()
        }
        return Result.success()
    }

    companion object {
        private const val ChannelId = "departure_reminders"
        private const val NotificationId = 0
        private const val ExpiryAfterDepartureMillis = 5 * 60 * 1000L

        fun clearPosted(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.filter { it.notification.channelId == ChannelId }.forEach {
                manager.cancel(it.tag, it.id)
            }
        }

        fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                ChannelId,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.reminder_channel_description) }
            manager.createNotificationChannel(channel)
        }
    }
}

private data class ReminderContent(
    val title: String,
    val summary: String,
    val routeName: String,
    val departureTime: String,
    val bikeTypes: String,
    val bikeTypesDescription: String,
    val borrowValue: String,
    val borrowCompactValue: String,
    val borrowCompactLabel: String,
    val borrowForecast: String?,
    val returnValue: String,
    val returnCompactValue: String,
    val returnCompactLabel: String,
    val returnForecast: String?,
    val borrowStationName: String,
    val returnStationName: String,
    val borrowBackup: String?,
    val returnBackup: String?,
    val status: ReminderStatus,
)

private data class ReminderStatus(
    val text: String,
    val shortText: String,
    val background: Int,
)

private fun reminderContent(
    context: Context,
    routeName: String,
    departureTime: String,
    borrowStationName: String,
    returnStationName: String,
    borrowStation: BikeStation?,
    returnStation: BikeStation?,
    borrowBackup: BackupRecommendation?,
    returnBackup: BackupRecommendation?,
    bikeDisplay: BikeKind,
    bikeThreshold: BikeKind,
    lowBikeThreshold: Int,
    lowDockThreshold: Int,
    borrowForecast: AvailabilityForecast?,
    returnForecast: AvailabilityForecast?,
    returnTime: String?,
    showForecast: Boolean,
): ReminderContent {
    val bikes = borrowStation?.observation?.counts?.let(bikeThreshold::count)
    val docks = returnStation?.observation?.counts?.returnDocks
    val borrowValue = currentCountValue(context, bikes, needsBorrowBackup(borrowStation, lowBikeThreshold, bikeThreshold))
    val title = context.getString(R.string.reminder_title, routeName, departureTime)
    val borrowForecastLine = if (showForecast) forecastLine(context, borrowForecast, departureTime) else null
    val returnForecastLine = if (showForecast) {
        if (returnTime == null) context.getString(R.string.reminder_return_time_needed)
        else forecastLine(context, returnForecast, returnTime)
    } else null
    val currentSummary = when {
        borrowBackup != null && returnBackup != null -> context.getString(R.string.reminder_backups_available)
        borrowBackup != null -> context.getString(
            R.string.reminder_borrow_backup_summary,
            borrowBackup.count,
            borrowBackup.ref.name,
        )
        returnBackup != null -> context.getString(
            R.string.reminder_return_backup_summary,
            returnBackup.count,
            returnBackup.ref.name,
        )
        bikes != null && docks != null -> context.getString(R.string.reminder_availability_summary, bikes, docks)
        else -> context.getString(R.string.reminder_route_summary, borrowStationName, returnStationName)
    }
    val summary = listOfNotNull(currentSummary, borrowForecastLine?.let { "🚲 $it" }, returnForecastLine?.let { "🅿️ $it" }).joinToString(" · ")
    val returnValue = currentCountValue(context, docks, needsReturnBackup(returnStation, lowDockThreshold))
    val primaryObservations = listOfNotNull(
        borrowStation?.observation,
        returnStation?.observation,
    )
    val status = when {
        primaryObservations.size < 2 -> ReminderStatus(
            text = context.getString(R.string.reminder_availability_unavailable),
            shortText = context.getString(R.string.reminder_status_offline_short),
            background = R.drawable.notification_status_unavailable,
        )
        primaryObservations.any { it.freshness != Freshness.Fresh } -> ReminderStatus(
            text = context.getString(R.string.reminder_availability_stale),
            shortText = context.getString(R.string.reminder_status_stale_short),
            background = R.drawable.notification_status_stale,
        )
        else -> ReminderStatus(
            text = context.getString(R.string.reminder_availability_live),
            shortText = context.getString(R.string.reminder_status_live_short),
            background = R.drawable.notification_status_live,
        )
    }
    return ReminderContent(
        title = title,
        summary = summary,
        routeName = routeName,
        departureTime = departureTime,
        bikeTypes = when (bikeDisplay) {
            BikeKind.All -> context.getString(R.string.reminder_bike_types,
                borrowStation?.observation?.counts?.regularBikes?.toString() ?: "—",
                borrowStation?.observation?.counts?.electricBikes?.toString() ?: "—")
            BikeKind.Regular -> "🚲 ${borrowStation?.observation?.counts?.regularBikes ?: "—"}"
            BikeKind.Electric -> "⚡ ${borrowStation?.observation?.counts?.electricBikes ?: "—"}"
        },
        bikeTypesDescription = when (bikeDisplay) {
            BikeKind.All -> context.getString(R.string.reminder_bike_types_description,
                borrowStation?.observation?.counts?.regularBikes?.toString() ?: "—",
                borrowStation?.observation?.counts?.electricBikes?.toString() ?: "—")
            BikeKind.Regular -> context.getString(R.string.reminder_regular_description, bikes?.toString() ?: "—")
            BikeKind.Electric -> context.getString(R.string.reminder_electric_description, bikes?.toString() ?: "—")
        },
        borrowValue = borrowValue,
        borrowCompactValue = if (borrowForecast?.medianCount != null && bikes != null &&
            !needsBorrowBackup(borrowStation, lowBikeThreshold, bikeThreshold)
        ) {
            context.getString(R.string.reminder_current_to_forecast, bikes, borrowForecast.medianCount)
        } else {
            borrowValue
        },
        borrowCompactLabel = if (borrowForecast?.medianCount != null) {
            context.getString(R.string.reminder_borrow_at, departureTime.takeLast(5))
        } else context.getString(when (bikeThreshold) {
            BikeKind.All -> R.string.reminder_all_bikes
            BikeKind.Regular -> R.string.reminder_regular_label
            BikeKind.Electric -> R.string.reminder_electric_label
        }),
        borrowForecast = borrowForecastLine,
        returnValue = returnValue,
        returnCompactValue = if (returnForecast?.medianCount != null && docks != null &&
            !needsReturnBackup(returnStation, lowDockThreshold)
        ) context.getString(R.string.reminder_current_to_forecast, docks, returnForecast.medianCount) else returnValue,
        returnForecast = returnForecastLine,
        returnCompactLabel = if (returnForecast?.medianCount != null && returnTime != null) {
            context.getString(R.string.reminder_return_at, returnTime.takeLast(5))
        } else context.getString(R.string.reminder_return_icon),
        borrowStationName = borrowStationName,
        returnStationName = returnStationName,
        borrowBackup = borrowBackup?.let {
            context.getString(R.string.reminder_backup_line, it.count, it.ref.name)
        },
        returnBackup = returnBackup?.let {
            context.getString(R.string.reminder_backup_line, it.count, it.ref.name)
        },
        status = status,
    )
}

private fun reminderForecastRequest(
    station: BikeStation,
    metric: AvailabilityMetric,
    arrival: Instant,
    threshold: Int,
) = ForecastRequest(
    station.id, metric, ArrivalDistribution(listOf(ArrivalPoint(arrival, 1.0)), ArrivalSource.UserEstimate), threshold,
)

private fun forecastLine(context: Context, forecast: AvailabilityForecast?, time: String): String =
    when (forecast?.status) {
        ForecastStatus.Experimental, ForecastStatus.Validated -> context.getString(
            R.string.reminder_forecast_estimate, time, forecast.medianCount, forecast.p10Count, forecast.p90Count,
        )
        ForecastStatus.Building -> context.getString(R.string.reminder_forecast_building)
        else -> context.getString(
            if (forecast?.reasonCodes?.contains("UNSUPPORTED_HORIZON") == true) R.string.reminder_forecast_horizon
            else R.string.reminder_forecast_unavailable,
        )
    }

private fun reminderTime(context: Context, instant: Instant): String {
    val zone = ZoneId.of("Asia/Taipei")
    val time = instant.atZone(zone)
    val formatted = time.format(DateTimeFormatter.ofPattern("HH:mm"))
    return if (time.toLocalDate() > LocalDate.now(zone)) {
        context.getString(R.string.reminder_tomorrow_time, formatted)
    } else formatted
}

private fun currentCountValue(context: Context, count: Int?, warning: Boolean): String =
    if (count == null) {
        context.getString(R.string.reminder_value_unavailable)
    } else {
        context.getString(if (warning) R.string.reminder_low_count_value else R.string.reminder_count_value, count)
    }

private fun reminderCompactView(context: Context, content: ReminderContent): RemoteViews =
    RemoteViews(context.packageName, R.layout.notification_departure_compact).apply {
        bindCommonReminderContent(content)
        setTextViewText(R.id.reminder_freshness, content.status.shortText)
        setTextViewText(R.id.reminder_borrow_value, content.borrowCompactValue)
        setTextViewText(R.id.reminder_borrow_timing, content.borrowCompactLabel)
        setContentDescription(R.id.reminder_borrow_timing, "${context.getString(R.string.reminder_borrow_label)} ${content.borrowCompactLabel}")
        setTextViewText(R.id.reminder_return_value, content.returnCompactValue)
        setTextViewText(R.id.reminder_return_timing, content.returnCompactLabel)
        setContentDescription(R.id.reminder_return_timing, "${context.getString(R.string.reminder_return_label)} ${content.returnCompactLabel}")
    }

private fun reminderExpandedView(context: Context, content: ReminderContent): RemoteViews =
    RemoteViews(context.packageName, R.layout.notification_departure_expanded).apply {
        bindCommonReminderContent(content)
        setContentDescription(R.id.reminder_borrow_symbol, context.getString(R.string.reminder_borrow_label))
        setContentDescription(R.id.reminder_return_symbol, context.getString(R.string.reminder_return_label))
        setTextViewText(R.id.reminder_borrow_now, context.getString(R.string.reminder_all_bikes))
        setTextViewText(R.id.reminder_return_now, context.getString(R.string.reminder_now))
        setTextViewText(R.id.reminder_borrow_station, content.borrowStationName)
        setTextViewText(R.id.reminder_borrow_forecast, content.borrowForecast.orEmpty())
        setViewVisibility(R.id.reminder_borrow_forecast, if (content.borrowForecast == null) View.GONE else View.VISIBLE)
        setTextViewText(R.id.reminder_return_forecast, content.returnForecast.orEmpty())
        setViewVisibility(R.id.reminder_return_forecast, if (content.returnForecast == null) View.GONE else View.VISIBLE)
        setTextViewText(R.id.reminder_return_station, content.returnStationName)
        setTextViewText(R.id.reminder_borrow_backup, content.borrowBackup.orEmpty())
        setViewVisibility(
            R.id.reminder_borrow_backup,
            if (content.borrowBackup == null) View.GONE else View.VISIBLE,
        )
        setTextViewText(R.id.reminder_return_backup, content.returnBackup.orEmpty())
        setViewVisibility(
            R.id.reminder_return_backup,
            if (content.returnBackup == null) View.GONE else View.VISIBLE,
        )
    }

private fun RemoteViews.bindCommonReminderContent(content: ReminderContent) {
    setViewVisibility(R.id.reminder_freshness, if (content.status.background == R.drawable.notification_status_live) View.GONE else View.VISIBLE)
    setTextViewText(R.id.reminder_bike_types, content.bikeTypes)
    setContentDescription(R.id.reminder_bike_types, content.bikeTypesDescription)
    setTextViewText(R.id.reminder_route_name, content.routeName)
    setTextViewText(R.id.reminder_departure_time, content.departureTime)
    setTextViewText(R.id.reminder_borrow_value, content.borrowValue)
    setTextViewText(R.id.reminder_return_value, content.returnValue)
    setTextViewText(R.id.reminder_freshness, content.status.text)
    setContentDescription(R.id.reminder_freshness, content.status.text)
    setInt(R.id.reminder_freshness, "setBackgroundResource", content.status.background)
}

private data class ReminderStationRef(val id: String, val name: String)

private data class BackupRecommendation(
    val ref: ReminderStationRef,
    val station: BikeStation,
    val count: Int,
)

private fun RouteStation.reminderRef() = ReminderStationRef(id.value, name)

private suspend fun ReminderDataProvider.stationsForReminder(
    refs: List<ReminderStationRef>,
): Map<String, BikeStation> = coroutineScope {
    refs.distinctBy(ReminderStationRef::id)
        .filter { it.id.isNotBlank() }
        .map { ref -> async { ref.id to stationForReminder(ref.id) } }
        .mapNotNull { request ->
            val (id, station) = request.await()
            station?.let { id to it }
        }
        .toMap()
}

private fun needsBorrowBackup(station: BikeStation?, threshold: Int, kind: BikeKind): Boolean =
    station.availableBikes(kind).isRisky(station, threshold)

private fun needsReturnBackup(station: BikeStation?, threshold: Int): Boolean =
    station.availableDocks().isRisky(station, threshold)

private fun Int?.isRisky(station: BikeStation?, threshold: Int): Boolean =
    this == null || this <= threshold ||
        station?.observation?.serviceStatus != StationServiceStatus.Operating || station?.observation?.freshness != Freshness.Fresh

private fun BikeStation?.availableBikes(kind: BikeKind): Int? = this?.observation?.counts?.let(kind::count)

private fun BikeStation?.availableDocks(): Int? = this?.observation?.counts?.returnDocks

private fun selectBackup(
    refs: List<ReminderStationRef>,
    stations: Map<String, BikeStation>,
    threshold: Int,
    count: (BikeStation) -> Int?,
): BackupRecommendation? {
    return refs.mapNotNull { ref ->
        val station = stations[ref.id] ?: return@mapNotNull null
        val observation = station.observation ?: return@mapNotNull null
        val available = count(station) ?: return@mapNotNull null
        if (observation.serviceStatus != StationServiceStatus.Operating ||
            observation.freshness != Freshness.Fresh || available <= threshold
        ) {
            return@mapNotNull null
        }
        BackupRecommendation(ref, station, available)
    }.firstOrNull()
}

private object ReminderData {
    const val RouteId = "route_id"
    const val RouteRevision = "route_revision"
    const val DepartureEpochMillis = "departure_epoch_millis"
}
