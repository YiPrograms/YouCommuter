package dev.yikuo.youcommuter.core.data

import dev.yikuo.youcommuter.core.domain.PredictionSettings
import dev.yikuo.youcommuter.core.model.PredictionServiceConfiguration
import dev.yikuo.youcommuter.core.model.FailureReason
import dev.yikuo.youcommuter.core.model.ForecastRequest
import dev.yikuo.youcommuter.core.model.ForecastRisk
import dev.yikuo.youcommuter.core.model.ForecastStatus
import dev.yikuo.youcommuter.core.model.StationResult
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import kotlinx.serialization.SerializationException
import org.junit.Test

class RemoteForecastRepositoryTest {
    private val json = Json
    private fun fixture() = json.decodeFromString<ForecastBatchResponseDto>(
        File(System.getProperty("youcommuter.fixtures"), "forecast-batch.json").readText(),
    )

    @Test fun `shared fixture preserves estimate range and absent probabilities`() {
        val forecast = fixture().forecasts.single().toDomain()
        assertEquals(ForecastStatus.Experimental, forecast.status)
        assertEquals(9, forecast.medianCount)
        assertEquals(12, forecast.observation!!.counts.totalBikes)
        assertNull(forecast.probabilityEmpty)
        assertNull(forecast.probabilityAtOrBelowThreshold)
        assertEquals(ForecastRisk.Unknown, forecast.risk)
    }

    @Test fun `prediction protocol rejects unrecognized fields and missing required fields`() {
        val body = File(System.getProperty("youcommuter.fixtures"), "forecast-batch.json").readText()
        val extra = body.replaceFirst("{", "{\"oldFormat\":true,")
        val missing = body.replace(",\n        \"distinctConfirmedRides\": 0", "")
        for (invalid in listOf(extra, missing)) {
            assertThrows(SerializationException::class.java) { json.decodeFromString<ForecastBatchResponseDto>(invalid) }
        }
    }

    @Test fun `building state has no numerical estimate`() {
        val forecast = fixture().forecasts.single().copy(
            status = "building", medianCount = null, p10Count = null, p90Count = null,
        ).toDomain()
        assertEquals(ForecastStatus.Building, forecast.status)
        assertNull(forecast.medianCount)
    }

    @Test fun `forecast request uses only configured service key and absolute arrival`() = runTest {
        val response = fixture()
        val request = request(response)
        var authorization: String? = null
        var captured: ForecastBatchRequestDto? = null
        val repository = repository { header, body ->
            authorization = header
            captured = body
            response
        }
        assertTrue(repository.forecasts(listOf(request)) is StationResult.Success)
        assertEquals("Bearer service-key", authorization)
        val capturedItem = requireNotNull(captured).items.single()
        assertEquals(request.arrival.points.single().at, Instant.parse(capturedItem.arrival.points.single().at))
        assertEquals("rent_total", capturedItem.metric)
    }

    @Test fun `mismatched or malformed response becomes a recoverable failure`() = runTest {
        val response = fixture()
        val original = response.forecasts.single()
        for (invalid in listOf(
            original.copy(stationId = "another-station"),
            original.copy(metric = "future_metric"),
            original.copy(riskBand = "future_risk"),
            original.copy(observation = original.observation!!.copy(stationId = "another-station")),
            original.copy(observation = original.observation!!.copy(status = "future_status")),
            original.copy(observation = original.observation!!.copy(quality = "future_quality")),
            original.copy(observation = original.observation!!.copy(timestampQuality = "future_timestamp")),
            original.copy(p10Count = 15),
            original.copy(probabilityEmpty = 0.5),
            original.copy(validUntil = "not-a-timestamp"),
        )) {
            val repository = repository { _, _ -> ForecastBatchResponseDto(listOf(invalid)) }
            assertEquals(
                StationResult.Failure(FailureReason.InvalidResponse),
                repository.forecasts(listOf(request(response))),
            )
        }
    }

    @Test fun `network failure falls back without swallowing cancellation`() = runTest {
        val request = request(fixture())
        assertEquals(
            StationResult.Failure(FailureReason.Network),
            repository { _, _ -> throw IOException() }.forecasts(listOf(request)),
        )
        var cancelled = false
        try {
            repository { _, _ -> throw CancellationException() }.forecasts(listOf(request))
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test fun `disabled service makes no request and old provider responses are discarded`() = runTest {
        val settings = TestPredictionSettings()
        val response = fixture()
        var calls = 0
        var capturedEndpoint = ""
        val repository = RemoteForecastRepository(object : ForecastApi {
            override suspend fun forecasts(endpoint: String, authorization: String?, request: ForecastBatchRequestDto): ForecastBatchResponseDto {
                calls++
                capturedEndpoint = endpoint
                assertNull(authorization)
                settings.save(PredictionServiceConfiguration("https://second.test/predict"))
                return response
            }
        }, settings)
        val requests = listOf(request(response))
        assertEquals(StationResult.Failure(FailureReason.NotConfigured), repository.forecasts(requests))
        assertEquals(0, calls)
        settings.save(PredictionServiceConfiguration("https://first.test/predict"))
        assertEquals(StationResult.Failure(FailureReason.NotConfigured), repository.forecasts(requests))
        assertEquals("https://first.test/predict", capturedEndpoint)
        assertEquals(1, calls)
    }

    private fun request(response: ForecastBatchResponseDto): ForecastRequest {
        val forecast = response.forecasts.single().toDomain()
        return ForecastRequest(forecast.stationId, forecast.metric, forecast.arrival, forecast.lowCountThreshold)
    }

    private fun repository(block: suspend (String?, ForecastBatchRequestDto) -> ForecastBatchResponseDto) =
        RemoteForecastRepository(
            object : ForecastApi {
                override suspend fun forecasts(endpoint: String, authorization: String?, request: ForecastBatchRequestDto) =
                    block(authorization, request)
            },
            TestPredictionSettings(PredictionServiceConfiguration("https://service.test/v1/forecasts:batch", "service-key")),
        )
}
