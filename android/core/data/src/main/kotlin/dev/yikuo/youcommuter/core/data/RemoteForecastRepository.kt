package dev.yikuo.youcommuter.core.data

import dev.yikuo.youcommuter.core.domain.ForecastRepository
import dev.yikuo.youcommuter.core.domain.PredictionSettings
import dev.yikuo.youcommuter.core.model.AvailabilityForecast
import dev.yikuo.youcommuter.core.model.FailureReason
import dev.yikuo.youcommuter.core.model.ForecastRequest
import dev.yikuo.youcommuter.core.model.PredictionServiceConfiguration
import dev.yikuo.youcommuter.core.model.StationResult
import java.io.IOException
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

internal class RemoteForecastRepository(private val api: ForecastApi, private val settings: PredictionSettings) : ForecastRepository {
    override suspend fun forecasts(requests: List<ForecastRequest>): StationResult<List<AvailabilityForecast>> {
        val revision = settings.revision
        val result = request(settings.load(), requests)
        // Responses from a previous service must not appear after changing configuration.
        return if (settings.revision == revision) result else StationResult.Failure(FailureReason.NotConfigured)
    }

    internal suspend fun request(configuration: PredictionServiceConfiguration, requests: List<ForecastRequest>): StationResult<List<AvailabilityForecast>> {
        if (configuration.endpoint.isBlank()) return StationResult.Failure(FailureReason.NotConfigured)
        return try {
            require(requests.size in 1..12)
            val response = api.forecasts(configuration.endpoint,
                configuration.apiKey.takeIf { it.isNotEmpty() }?.let { "Bearer $it" },
                ForecastBatchRequestDto(requests.map { it.toDto() }))
            val forecasts = response.forecasts.map { it.toDomain() }
            require(forecasts.size == requests.size)
            forecasts.zip(requests).forEach { (forecast, request) ->
                require(forecast.stationId == request.stationId && forecast.metric == request.metric)
                require(forecast.arrival == request.arrival && forecast.lowCountThreshold == request.lowCountThreshold)
            }
            StationResult.Success(forecasts)
        } catch (_: IOException) { StationResult.Failure(FailureReason.Network)
        } catch (_: HttpException) { StationResult.Failure(FailureReason.Service)
        } catch (_: SerializationException) { StationResult.Failure(FailureReason.InvalidResponse)
        } catch (_: IllegalArgumentException) { StationResult.Failure(FailureReason.InvalidResponse)
        } catch (_: DateTimeParseException) { StationResult.Failure(FailureReason.InvalidResponse) }
    }
}
