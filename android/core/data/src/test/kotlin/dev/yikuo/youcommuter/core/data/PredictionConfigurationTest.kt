package dev.yikuo.youcommuter.core.data

import dev.yikuo.youcommuter.core.domain.PredictionSettings
import dev.yikuo.youcommuter.core.model.PredictionServiceConfiguration
import org.junit.Assert.*
import org.junit.Test

internal class TestPredictionSettings(var configuration: PredictionServiceConfiguration = PredictionServiceConfiguration()) : PredictionSettings {
    override var revision = 0L
    override fun load() = configuration
    override suspend fun save(configuration: PredictionServiceConfiguration) { this.configuration = configuration; revision++ }
}
class PredictionConfigurationTest {
    @Test fun `only explicit local debug HTTP is accepted`() {
        assertEquals("", validatePredictionConfiguration(PredictionServiceConfiguration("", "unused")).apiKey)
        for (url in listOf("https://user:password@host.test/predict", "https://host.test/predict?key=secret", "http://192.168.0.2/predict", "file:///tmp/test")) {
            assertTrue(runCatching { validatePredictionConfiguration(PredictionServiceConfiguration(url), true) }.isFailure)
        }
        val local = PredictionServiceConfiguration("http://10.0.2.2:18081/v1/forecasts:batch")
        assertTrue(runCatching { validatePredictionConfiguration(local) }.isFailure)
        assertEquals(local, validatePredictionConfiguration(local, true))
        assertTrue(runCatching { validatePredictionConfiguration(PredictionServiceConfiguration("https://host.test/predict", "secret\nheader")) }.isFailure)
    }
}
