package dev.yikuo.youcommuter.core.domain

import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class AvailabilityFreshnessTest {
    @Test fun `shared source freshness cases agree with backend`() {
        val cases = Json.parseToJsonElement(File(System.getProperty("youcommuter.fixtures"), "freshness.json").readText()).jsonArray
        for (element in cases) {
            val case = element.jsonObject
            val observed = case.getValue("observedAt").takeUnless { it is JsonNull }?.jsonPrimitive?.content?.let(Instant::parse)
            val actual = AvailabilityFreshness.classify(
                observedAt = observed,
                now = Instant.parse(case.getValue("now").jsonPrimitive.content),
                cadence = Duration.ofSeconds(case.getValue("cadenceSeconds").jsonPrimitive.content.toLong()),
            )
            assertEquals(case.getValue("name").jsonPrimitive.content, case.getValue("expected").jsonPrimitive.content, actual.name.lowercase())
        }
    }
}
