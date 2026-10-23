package dev.yikuo.youcommuter.core.data

import dev.yikuo.youcommuter.core.domain.CoverageRepository
import dev.yikuo.youcommuter.core.domain.ForecastRepository
import dev.yikuo.youcommuter.core.domain.PredictionSettings
import dev.yikuo.youcommuter.core.domain.StationRepository
import dev.yikuo.youcommuter.core.model.*
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Validates a complete prediction URL, including its path. */
fun validatePredictionConfiguration(configuration: PredictionServiceConfiguration, allowLocalHttp: Boolean = false): PredictionServiceConfiguration {
    val endpoint = configuration.endpoint.trim()
    val key = configuration.apiKey.trim()
    require(key.length <= 512 && key.none { it == '\r' || it == '\n' })
    if (endpoint.isEmpty()) return PredictionServiceConfiguration()
    val url = requireNotNull(endpoint.toHttpUrlOrNull())
    require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.query == null)
    val local = url.host in setOf("10.0.2.2", "127.0.0.1", "localhost", "::1")
    require(url.isHttps || (allowLocalHttp && local))
    return PredictionServiceConfiguration(url.toString(), key)
}

data class RepositorySet(val coverage: CoverageRepository, val stations: StationRepository, val forecasts: ForecastRepository,
    val testPrediction: suspend (PredictionServiceConfiguration) -> StationResult<Unit>)

object YouCommuterRepositories {
    fun create(settings: PredictionSettings): RepositorySet {
        // Separate clients prevent service credentials or redirects from reaching YouBike.
        val inventoryClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
        val inventory = YouBikeInventory(inventoryClient)
        val predictionClient = inventoryClient.newBuilder().callTimeout(12, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body
                if (body != null && body.source().request(1024L * 1024 + 1)) {
                    response.close()
                    throw IOException("Prediction response exceeds size limit")
                }
                response
            }.build()
        val retrofit = Retrofit.Builder().baseUrl("https://prediction.invalid/").client(predictionClient)
            .addConverterFactory(Json.asConverterFactory("application/json".toMediaType())).build()
        val forecasts = RemoteForecastRepository(retrofit.create(ForecastApi::class.java), settings)
        return RepositorySet(inventory, inventory, forecasts) { configuration ->
            val request = ForecastRequest(StationId("youbike:500101001"), AvailabilityMetric.TotalBikes,
                ArrivalDistribution(listOf(ArrivalPoint(Instant.now().plusSeconds(15 * 60), 1.0)), ArrivalSource.UserEstimate), 3)
            when (val result = forecasts.request(configuration, listOf(request))) {
                is StationResult.Success -> StationResult.Success(Unit)
                is StationResult.Failure -> result
            }
        }
    }
}
