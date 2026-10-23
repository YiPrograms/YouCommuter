package dev.yikuo.youcommuter.core.data

import com.sun.net.httpserver.HttpServer
import dev.yikuo.youcommuter.core.model.*
import java.io.File
import java.net.InetSocketAddress
import java.time.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class YouBikeInventoryTest {
    private val root = File(System.getProperty("youcommuter.fixtures"))
    private fun fixture(name: String) = File(root, "youbike/$name.json").readText()

    @Test fun `location selection uses cached metadata without inventory refresh and searches address`() = runTest {
        FeedFixture().use { feed ->
            val inventory = feed.inventory
            val first = (inventory.locations("00", "") as StationResult.Success).value.single()
            assertEquals("youbike:500101001", first.id.value)
            assertEquals(25.02605, first.latitude, 0.000001)
            assertEquals("測試路一號", first.address)
            feed.clock.now = feed.clock.now.plusSeconds(600)
            assertEquals(listOf(first), (inventory.locations("00", "測試路") as StationResult.Success).value)
            assertTrue((inventory.locations("00", "不存在") as StationResult.Success).value.isEmpty())
            assertEquals("10", (inventory.locations("10", "") as StationResult.Success).value.single().regionCode)
            assertEquals(1, feed.requests.size)
            // Inventory still revalidates after its shorter TTL.
            assertEquals(5, (inventory.get(first.id.value) as StationResult.Success).value.observation!!.counts.totalBikes)
            assertEquals(2, feed.requests.size)
        }
    }

    @Test fun `location catalog expires after a day and refreshes renamed stations`() = runTest {
        FeedFixture().use { feed ->
            feed.inventory.locations("00", "")
            feed.clock.now = feed.clock.now.plusSeconds(86401)
            feed.text = fixture("stations").replace("測試科技大樓站", "新站名")
            assertEquals("新站名", (feed.inventory.locations("00", "") as StationResult.Success).value.single().name)
            assertEquals(2, feed.requests.size)
        }
    }

    @Test fun `batch reads share one snapshot skip missing ids and reclassify freshness`() = runTest {
        FeedFixture().use { feed ->
            val ids = linkedSetOf("youbike:500101001", "missing", "youbike:500109001")
            val initial = (feed.inventory.getMany(ids) as StationResult.Success).value
            assertEquals(2, initial.size)
            assertEquals(1, feed.requests.size)
            assertEquals("00", (feed.inventory.location(initial.first().id.value) as StationResult.Success).value.regionCode)
            feed.clock.now = feed.clock.now.plusSeconds(11 * 60)
            val later = (feed.inventory.getMany(ids) as StationResult.Success).value
            assertTrue(later.all { it.observation!!.freshness == Freshness.Stale })
            assertEquals(initial.map { it.id }, later.map { it.id })
            assertEquals(2, feed.requests.size)
        }
    }

    @Test fun `manual refresh replaces cached bike and dock counts immediately without restarting the repository`() = runTest {
        FeedFixture().use { feed ->
            val inventory = feed.inventory
            val id = "youbike:500101001"
            val original = (inventory.get(id) as StationResult.Success).value
            feed.text = updatedFeed(total = 18, docks = 11)
            assertEquals(5, (inventory.get(id) as StationResult.Success).value.observation!!.counts.totalBikes)

            assertEquals(StationResult.Success(Unit), inventory.refresh())
            val updated = (inventory.get(id) as StationResult.Success).value
            assertEquals(AvailabilityCounts(18, 16, 2, 11), updated.observation!!.counts)
            val page = (inventory.search("00", "測試路", null, 20) as StationResult.Success).value
            assertEquals(updated, page.stations.single())
            assertEquals(2, feed.requests.size)
            val request = feed.requests.last()
            assertNull(request.etag)
            assertNull(request.modified)
            assertTrue(request.cacheControl.orEmpty().contains("no-cache"))
            assertEquals(1, feed.areaCalls.get())

            feed.text = updatedFeed(total = 0, docks = 29)
            assertEquals(StationResult.Success(Unit), inventory.refresh())
            assertEquals(0, (inventory.get(id) as StationResult.Success).value.observation!!.counts.totalBikes)
            assertEquals(original.id, updated.id)
            assertEquals(3, feed.requests.size)
        }
    }

    @Test fun `failed refresh reports failure without replacing the accepted snapshot and can be retried`() = runTest {
        FeedFixture().use { feed ->
            val inventory = feed.inventory
            val id = "youbike:500101001"
            val original = (inventory.get(id) as StationResult.Success).value
            feed.clock.now = feed.clock.now.plusSeconds(10)
            feed.text = "<html>unavailable</html>"
            assertEquals(StationResult.Failure(FailureReason.InvalidResponse), inventory.refresh())
            assertEquals(original, (inventory.get(id) as StationResult.Success).value)
            feed.text = updatedFeed(total = 18, docks = 11)
            assertEquals(StationResult.Success(Unit), inventory.refresh())
            assertEquals(18, (inventory.get(id) as StationResult.Success).value.observation!!.counts.totalBikes)
        }
    }

    @Test fun `manual refresh respects retry after even while a cached snapshot is available`() = runTest {
        FeedFixture().use { feed ->
            feed.inventory.get("youbike:500101001")
            feed.status = 429
            assertEquals(StationResult.Failure(FailureReason.Service), feed.inventory.refresh())
            val calls = feed.requests.size
            feed.status = 200
            assertEquals(StationResult.Failure(FailureReason.Service), feed.inventory.refresh())
            assertEquals(calls, feed.requests.size)
            feed.clock.now = feed.clock.now.plusSeconds(121)
            assertEquals(StationResult.Success(Unit), feed.inventory.refresh())
            assertEquals(calls + 1, feed.requests.size)
        }
    }

    @Test fun `moving the device clock backwards does not keep a snapshot cached indefinitely`() = runTest {
        FeedFixture().use { feed ->
            feed.inventory.get("youbike:500101001")
            feed.clock.now = feed.clock.now.minusSeconds(3600)
            feed.inventory.get("youbike:500101001")
            assertEquals(2, feed.requests.size)
        }
    }

    private fun updatedFeed(total: Int, docks: Int): String {
        val rows = Json.parseToJsonElement(fixture("stations")).jsonArray.toMutableList()
        rows[0] = JsonObject(rows[0].jsonObject.toMutableMap().apply {
            put("available_spaces", JsonPrimitive(total))
            put("empty_spaces", JsonPrimitive(docks))
            put("available_spaces_detail", buildJsonObject {
                put("yb2", total - minOf(total, 2))
                put("eyb", minOf(total, 2))
            })
        })
        return JsonArray(rows).toString()
    }

    private class TestClock(@Volatile var now: Instant) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private data class FeedRequest(val etag: String?, val modified: String?, val cacheControl: String?)

    private inner class FeedFixture : AutoCloseable {
        val clock = TestClock(Instant.parse("2026-10-19T15:03:40Z"))
        val requests = CopyOnWriteArrayList<FeedRequest>()
        val areaCalls = AtomicInteger()
        @Volatile var text = fixture("stations")
        @Volatile var status = 200
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val inventory = YouBikeInventory(OkHttpClient(), "http://127.0.0.1:${server.address.port}/", clock)

        init {
            server.createContext("/") { exchange ->
                val areas = exchange.requestURI.path.endsWith("area-all.json")
                if (areas) areaCalls.incrementAndGet() else requests += FeedRequest(
                    exchange.requestHeaders.getFirst("If-None-Match"),
                    exchange.requestHeaders.getFirst("If-Modified-Since"),
                    exchange.requestHeaders.getFirst("Cache-Control"),
                )
                // Deliberately stable validators reproduce intermediaries returning an old 304.
                if (!areas && status == 429) {
                    exchange.responseHeaders.set("Retry-After", "120")
                    exchange.sendResponseHeaders(429, -1)
                } else if (!areas && exchange.requestHeaders.getFirst("If-None-Match") == "v1") {
                    exchange.sendResponseHeaders(304, -1)
                } else {
                    val bytes = (if (areas) fixture("areas") else text).toByteArray()
                    exchange.responseHeaders.set("ETag", "v1")
                    exchange.responseHeaders.set("Last-Modified", "Mon, 19 Oct 2026 15:03:00 GMT")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                exchange.close()
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }
    @Test fun `direct feed maps bike types and honest docks with anonymous caching`() = runTest {
        var now = Instant.parse("2026-10-19T15:03:40Z")
        val clock = object : Clock() {
            override fun instant() = now
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
        }
        val feedCalls = AtomicInteger(); val areaCalls = AtomicInteger()
        var invalid = false
        var limited = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            assertNull(exchange.requestHeaders.getFirst("Authorization"))
            val areas = exchange.requestURI.path.endsWith("area-all.json")
            if (areas) areaCalls.incrementAndGet() else feedCalls.incrementAndGet()
            if (!areas && limited) {
                exchange.responseHeaders.set("Retry-After", "120")
                exchange.sendResponseHeaders(429, -1)
            } else if (!areas && !invalid && exchange.requestHeaders.getFirst("If-None-Match") == "v1") {
                exchange.sendResponseHeaders(304, -1)
            } else {
                val text = if (invalid && !areas) "<html>failed</html>" else fixture(if (areas) "areas" else "stations")
                exchange.responseHeaders.set("ETag", "v1")
                exchange.sendResponseHeaders(200, text.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(text.toByteArray()) }
            }
            exchange.close()
        }
        server.start()
        try {
            val inventory = YouBikeInventory(OkHttpClient(), "http://127.0.0.1:${server.address.port}/", clock)
            val coverage = inventory.getCoverage() as CoverageResult.Success
            assertEquals(listOf("00", "10"), coverage.regions.map { it.code })
            val station = (inventory.get("youbike:500101001") as StationResult.Success).value
            val observation = requireNotNull(station.observation)
            assertEquals(AvailabilityCounts(5, 3, 2, 24), observation.counts)
            assertEquals(30, station.capacity)
            assertEquals(Freshness.Fresh, observation.freshness)
            val page = (inventory.search("00", "測試路", null, 20) as StationResult.Success).value
            assertEquals(station.id, page.stations.single().id)
            assertEquals(1, feedCalls.get()); assertEquals(1, areaCalls.get())
            now = now.plusSeconds(61)
            val unchanged = (inventory.get(station.id.value) as StationResult.Success).value
            assertEquals(observation.fetchedAt, requireNotNull(unchanged.observation).fetchedAt)
            assertEquals(observation.observedAt, requireNotNull(unchanged.observation).observedAt)
            now = now.plusSeconds(10 * 60)
            assertEquals(Freshness.Stale, (inventory.get(station.id.value) as StationResult.Success).value.observation!!.freshness)
            invalid = true; now = now.plusSeconds(61)
            assertEquals(StationResult.Failure(FailureReason.InvalidResponse), inventory.get(station.id.value))
            invalid = false
            assertTrue(inventory.get(station.id.value) is StationResult.Success)
            now = now.plusSeconds(61); limited = true
            assertEquals(StationResult.Failure(FailureReason.Service), inventory.get(station.id.value))
            val attempts = feedCalls.get(); limited = false
            assertEquals(StationResult.Failure(FailureReason.Service), inventory.get(station.id.value))
            assertEquals(attempts, feedCalls.get())
            now = now.plusSeconds(121)
            assertTrue(inventory.get(station.id.value) is StationResult.Success)

        } finally { server.stop(0) }
    }
    @Test fun `portable station versions match the service fixture`() {
        val cases = Json.parseToJsonElement(File(root, "station-version.json").readText()).jsonArray
        for (item in cases) {
            val c = item.jsonObject
            assertEquals(c.getValue("version").jsonPrimitive.content,
                stationVersion(c.getValue("capacity").jsonPrimitive.intOrNull,
                    c.getValue("systemType").jsonPrimitive.content,
                    c.getValue("latitude").jsonPrimitive.double, c.getValue("longitude").jsonPrimitive.double))
        }
    }
    @Test fun `missing time and types remain unknown and mismatched counts are discarded`() {
        val row = Json { ignoreUnknownKeys = true }.decodeFromString<List<Inventory>>(fixture("stations")).first()
        val missing = row.copy(time = null, detail = null).toStation(Instant.now()).observation!!
        assertNull(missing.observedAt); assertNull(missing.counts.electricBikes)
        assertEquals(Freshness.Unavailable, missing.freshness)
        val mismatch = row.copy(detail = BikeTypes(100, 2)).toStation(Instant.now()).observation!!
        assertEquals(5, mismatch.counts.totalBikes); assertNull(mismatch.counts.regularBikes)
    }
}
