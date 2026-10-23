package dev.yikuo.youcommuter.core.data

import dev.yikuo.youcommuter.core.domain.AvailabilityFreshness
import dev.yikuo.youcommuter.core.domain.CoverageRepository
import dev.yikuo.youcommuter.core.domain.StationRepository
import dev.yikuo.youcommuter.core.model.*
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.CacheControl

/** One shared, anonymous feed cache serves browsing, selection and notifications. */
internal class YouBikeInventory(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://apis.youbike.com.tw/",
    private val clock: Clock = Clock.systemUTC(),
) : StationRepository, CoverageRepository {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var accepted: Snapshot? = null
    private var rateLimitedUntil = java.time.Instant.MIN
    private data class Snapshot(
        val regions: List<Area>, val stations: List<BikeStation>, val checkedAt: java.time.Instant,
        val areasAt: java.time.Instant, val etag: String?, val modified: String?,
        val locations: Map<String, List<StationLocation>>,
        val byId: Map<String, BikeStation>,
        val byRegion: Map<String, List<BikeStation>>,
    )

    override suspend fun refresh(): StationResult<Unit> = request(forceRefresh = true) { Unit }

    override suspend fun locations(region: String, query: String): StationResult<List<StationLocation>> =
        request(maxAge = Duration.ofDays(1)) { snapshot ->
            val locations = snapshot.locations[region].orEmpty()
            if (query.isBlank()) locations else locations.filter {
                it.name.contains(query, ignoreCase = true) || it.address.contains(query, ignoreCase = true)
            }
        }

    override suspend fun location(id: String): StationResult<StationLocation> = request(maxAge = Duration.ofDays(1)) {
        requireNotNull(it.byId[id]).location()
    }

    override suspend fun getMany(ids: Set<String>): StationResult<List<BikeStation>> = request { snapshot ->
        val now = clock.instant()
        ids.mapNotNull { snapshot.byId[it]?.let { station -> freshen(station, now) } }
    }

    override suspend fun getCoverage(): CoverageResult = when (val result = request { snapshot ->
        snapshot.regions.map { area ->
            val stations = snapshot.byRegion[area.code].orEmpty()
            ServiceRegion(area.code, area.name, when {
                stations.isEmpty() -> CoverageStatus.Unavailable
                stations.any { freshen(it, clock.instant()).observation?.freshness == Freshness.Fresh } -> CoverageStatus.Available
                else -> CoverageStatus.Degraded
            }, stations.any { it.observation?.counts?.electricBikes != null })
        }
    }) {
        is StationResult.Success -> CoverageResult.Success(result.value)
        is StationResult.Failure -> CoverageResult.Failure(result.reason)
    }

    override suspend fun search(region: String, query: String, cursor: String?, limit: Int): StationResult<StationPage> = request { snapshot ->
        require(limit in 1..200)
        // Inventory refreshes do not invalidate pagination for the same search.
        val key = digest("$region|$query")
        val offset = if (cursor == null) 0 else {
            val parts = cursor.split(':'); require(parts.size == 2 && parts[0] == key)
            parts[1].toInt().also { require(it >= 0) }
        }
        val matches = snapshot.byRegion[region].orEmpty().filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true) || it.address.contains(query, ignoreCase = true) }
        val now = clock.instant()
        val page = matches.drop(offset).take(limit).map { freshen(it, now) }
        StationPage(page, if (offset + page.size < matches.size) "$key:${offset + page.size}" else null)
    }

    override suspend fun get(id: String): StationResult<BikeStation> = request { snapshot ->
        freshen(requireNotNull(snapshot.byId[id]), clock.instant())
    }

    private suspend fun <T> request(
        forceRefresh: Boolean = false,
        maxAge: Duration = Duration.ofMinutes(1),
        block: (Snapshot) -> T,
    ): StationResult<T> = try {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val snapshot = snapshot(forceRefresh, maxAge)
                StationResult.Success(block(snapshot))
            }
        }
    } catch (_: IOException) { StationResult.Failure(FailureReason.Network)
    } catch (_: FeedHTTPError) { StationResult.Failure(FailureReason.Service)
    } catch (_: SerializationException) { StationResult.Failure(FailureReason.InvalidResponse)
    } catch (_: IllegalArgumentException) { StationResult.Failure(FailureReason.InvalidResponse)
    } catch (_: DateTimeParseException) { StationResult.Failure(FailureReason.InvalidResponse) }

    private fun freshen(station: BikeStation, now: java.time.Instant): BikeStation {
        val observation = station.observation ?: return station
        val freshness = AvailabilityFreshness.classify(observation.observedAt, now, Duration.ofMinutes(1))
        return if (observation.freshness == freshness) station else station.copy(observation = observation.copy(freshness = freshness))
    }

    private fun snapshot(forceRefresh: Boolean, maxAge: Duration): Snapshot {
        val now = clock.instant()
        val previous = accepted
        val age = previous?.let { Duration.between(it.checkedAt, now) }
        if (!forceRefresh && previous != null && age != null && !age.isNegative && age < maxAge) return previous
        if (now < rateLimitedUntil) throw FeedHTTPError()
        val refreshAreas = previous == null || Duration.between(previous.areasAt, now) >= Duration.ofDays(1)
        var areas = if (refreshAreas) areas() else previous!!.regions
        val request = sourceRequest("json/station-yb2.json")
        if (forceRefresh) {
            // A refresh button must bypass both our TTL and conditional intermediary responses.
            request.cacheControl(CacheControl.FORCE_NETWORK)
        } else if (!refreshAreas) {
            previous?.etag?.let { request.header("If-None-Match", it) }
                ?: previous?.modified?.let { request.header("If-Modified-Since", it) }
        }
        client.newCall(request.build()).execute().use { response ->
            if (response.code == 304) {
                require(!forceRefresh && previous != null && !refreshAreas && (previous.etag != null || previous.modified != null))
                return previous.copy(checkedAt = clock.instant()).also { accepted = it }
            }
            checkStatus(response)
            val records = json.decodeFromString<List<Inventory>>(body(response))
            require(records.isNotEmpty() && records.size <= 50_000)
            if (records.any { row -> areas.none { it.code == row.area } }) areas = areas()
            require(records.map { it.number }.toSet().size == records.size)
            val receivedAt = clock.instant()
            val stations = records.map { row ->
                require(row.country == "00" && areas.any { it.code == row.area })
                row.toStation(receivedAt)
            }
            val locations = stations.map { it.location() }.groupBy { it.regionCode }.mapValues { (_, values) ->
                values.sortedWith(compareBy<StationLocation> { it.name }.thenBy { it.id.value })
            }
            return Snapshot(areas, stations, receivedAt, if (refreshAreas || areas != previous?.regions) receivedAt else previous!!.areasAt,
                response.header("ETag"), response.header("Last-Modified"), locations,
                stations.associateBy { it.id.value }, stations.groupBy { it.regionCode }.mapValues { (_, values) ->
                    values.sortedWith(compareBy<BikeStation> { it.name }.thenBy { it.id.value })
                }).also { accepted = it }
        }
    }

    private fun areas(): List<Area> = client.newCall(sourceRequest("json/area-all.json").build()).execute().use { response ->
        checkStatus(response)
        json.decodeFromString<List<Area>>(body(response)).filter { it.country == "00" }.also { areas ->
            require(areas.isNotEmpty() && areas.all { it.code.isNotBlank() && it.name.isNotBlank() })
            require(areas.map { it.code }.toSet().size == areas.size)
        }
    }

    private fun sourceRequest(path: String) = Request.Builder().url(baseUrl + path)
        .header("Accept", "application/json").header("User-Agent", "YouCommuter/1.0")

    private fun checkStatus(response: okhttp3.Response) {
        if (response.code == 429) {
            val now = clock.instant()
            val value = response.header("Retry-After").orEmpty()
            val seconds = value.toLongOrNull()?.takeIf { it in 0..604800 }
            val date = runCatching { java.time.ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            rateLimitedUntil = maxOf(now.plusSeconds(60), seconds?.let { now.plusSeconds(it) } ?: date ?: now.plusSeconds(60))
        }
        if (!response.isSuccessful) throw FeedHTTPError()
    }

    private fun body(response: okhttp3.Response): String {
        val source = requireNotNull(response.body).source()
        require(!source.request(32L * 1024 * 1024 + 1)) { "Feed exceeds size limit" }
        return source.readUtf8()
    }

    private class FeedHTTPError : RuntimeException()
}

@Serializable internal data class Area(
    @SerialName("country_code") val country: String,
    @SerialName("area_code") val code: String,
    @SerialName("area_name_tw") val name: String,
)
@Serializable internal data class Inventory(
    @SerialName("country_code") val country: String,
    @SerialName("area_code") val area: String,
    @SerialName("station_no") val number: String,
    @SerialName("name_tw") val name: String,
    @SerialName("address_tw") val address: String = "",
    val lat: String, val lng: String, val type: Int,
    @SerialName("parking_spaces") val capacity: Int? = null,
    val status: Int? = null,
    @SerialName("available_spaces") val bikes: Int? = null,
    @SerialName("empty_spaces") val docks: Int? = null,
    @SerialName("available_spaces_detail") val detail: BikeTypes? = null,
    val time: String? = null,
    @SerialName("updated_at") val updated: String,
) {
    fun toStation(fetchedAt: java.time.Instant): BikeStation {
        val latitude = lat.toDouble(); val longitude = lng.toDouble()
        require(number.isNotBlank() && name.isNotBlank() && latitude in -90.0..90.0 && longitude in -180.0..180.0)
        require(capacity == null || capacity >= 0)
        val system = when (type) { 1 -> StationSystemType.YouBike1; 2 -> StationSystemType.YouBike2; else -> StationSystemType.Unknown }
        fun valid(value: Int?) = value?.takeIf { it >= 0 && (capacity == null || it <= capacity) }
        val mismatch = bikes != null && detail?.regular != null && detail.electric != null && detail.regular.toLong() + detail.electric != bikes.toLong()
        val counts = AvailabilityCounts(valid(bikes), if (mismatch) null else valid(detail?.regular), if (mismatch) null else valid(detail?.electric), valid(docks))
        val observedAt = time?.takeIf { it.isNotBlank() }?.let(::feedTime)
        val id = StationId("youbike:$number")
        val version = stationVersion(capacity, when (system) { StationSystemType.YouBike1 -> "youbike_1"; StationSystemType.YouBike2 -> "youbike_2"; else -> "unknown" }, latitude, longitude)
        return BikeStation(id, name, address, area, latitude, longitude, capacity, system, null,
            StationObservation(id, digest("$number|$time|$counts|$status"), observedAt, feedTime(updated), fetchedAt, 60,
                if (observedAt == null) TimestampQuality.ProviderOnly else TimestampQuality.FeedSnapshot,
                when (status) { 1 -> StationServiceStatus.Operating; 2 -> StationServiceStatus.TemporarilyClosed; else -> StationServiceStatus.Unknown },
                counts, AvailabilityFreshness.classify(observedAt, fetchedAt, Duration.ofMinutes(1)), version))
    }
}
@Serializable internal data class BikeTypes(@SerialName("yb2") val regular: Int? = null, @SerialName("eyb") val electric: Int? = null)
private fun feedTime(value: String) = LocalDateTime.parse(value, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)).toInstant(ZoneOffset.ofHours(8))
internal fun stationVersion(capacity: Int?, system: String, latitude: Double, longitude: Double): String =
    digest("${capacity ?: "?"}|$system|${(latitude * 1_000_000).toLong()}|${(longitude * 1_000_000).toLong()}")
private fun digest(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(32) {
        for (i in 0 until 16) {
            val byte = bytes[i].toInt() and 255
            append(hex[byte ushr 4]); append(hex[byte and 15])
        }
    }
}
