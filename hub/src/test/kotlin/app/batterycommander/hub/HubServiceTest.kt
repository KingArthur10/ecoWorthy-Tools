package app.batterycommander.hub

import app.batterycommander.domain.BatteryGroup
import app.batterycommander.domain.BatteryReading
import app.batterycommander.domain.Favorite
import app.batterycommander.domain.StoredState
import app.batterycommander.domain.Wiring
import app.batterycommander.hub.store.HistoryDb
import app.batterycommander.hub.store.StateStore
import app.batterycommander.hub.web.OverviewDto
import app.batterycommander.hub.web.hubModule
import app.batterycommander.domain.DiscoveredDevice
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class HubServiceTest {
    private val dir: File = Files.createTempDirectory("hub-test").toFile()
    private val stateFile = File(dir, "state.json")
    private val history = HistoryDb(File(dir, "history.db"))
    private var now = 1_000_000L

    @AfterTest
    fun cleanup() {
        history.close()
        dir.deleteRecursively()
    }

    private fun reading(soc: Int) = BatteryReading(
        timestampMs = now, packVoltageMv = 13_200, currentMa = 0, stateOfChargePct = soc,
        remainingCapacityMah = 1000 * soc, nominalCapacityMah = 100_000, cycles = 0,
        cellVoltagesMv = List(4) { 3300 }, temperaturesDeciC = listOf(180), chargeFetOn = true,
        dischargeFetOn = true, balancingMask = 0, protections = emptyList(), productionDate = "2026-07-17",
        firmwareVersion = "6.6", model = "DP04S007L4S100A",
    )

    private fun service(favorites: List<Favorite>, reader: BatteryReader): HubService {
        StateStore(stateFile).save(StoredState(favorites = favorites, groups = listOf(BatteryGroup("g", "House", Wiring.PARALLEL))))
        return HubService(StateStore(stateFile), history, reader, clock = { now })
    }

    @Test
    fun refreshStoresReadingAndHistory() = runTest {
        val svc = service(listOf(Favorite("A", "Garage")), { reading(42) })
        assertTrue(svc.refresh("A").isSuccess)
        assertEquals(42, svc.state.value.readings["A"]!!.stateOfChargePct)
        assertEquals(PollStatus.State.IDLE, svc.statuses.value["A"]!!.state)
        assertEquals(listOf(42), history.history("A", 0).map { it.soc })
        // Persisted: a fresh store sees it.
        assertEquals(42, StateStore(stateFile).load().readings["A"]!!.stateOfChargePct)
    }

    @Test
    fun failedRefreshKeepsOldReadingAndReportsFailure() = runTest {
        var fail = false
        val svc = service(listOf(Favorite("A", "Garage"))) { if (fail) error("le-connection-abort-by-local") else reading(42) }
        svc.refresh("A")
        fail = true
        assertTrue(svc.refresh("A").isFailure)
        assertEquals(42, svc.state.value.readings["A"]!!.stateOfChargePct)
        val status = svc.statuses.value["A"]!!
        assertEquals(PollStatus.State.FAILED, status.state)
        assertEquals("le-connection-abort-by-local", status.message)
    }

    @Test
    fun pollerReadsStaleBatteriesAndBacksOffAfterFailure() = runTest {
        val reads = mutableListOf<String>()
        val svc = service(listOf(Favorite("A", "a"), Favorite("B", "b"))) { mac ->
            reads += mac
            if (mac == "B") error("out of range") else reading(50)
        }
        val job = launch { svc.runPoller(checkEvery = 30.seconds, failureBackoff = 2.minutes) }
        advanceTimeBy(1.seconds)
        assertEquals(listOf("A", "B"), reads) // both stale at start

        now += 60_000; advanceTimeBy(30.seconds)
        assertEquals(listOf("A", "B"), reads) // A fresh, B backing off

        now += 120_000; advanceTimeBy(30.seconds)
        assertEquals(listOf("A", "B", "B"), reads) // B retried after backoff; A still fresh

        now += 10 * 60_000; advanceTimeBy(30.seconds)
        assertEquals(listOf("A", "B", "B", "A", "B"), reads) // A due again
        job.cancel()
    }

    @Test
    fun manualModeLeavesTheRadioIdle() = runTest {
        val reads = mutableListOf<String>()
        val svc = service(listOf(Favorite("A", "a"))) { mac -> reads += mac; reading(50) }
        svc.setPollInterval(0)
        val job = launch { svc.runPoller(checkEvery = 30.seconds) }
        advanceTimeBy(1.seconds)
        now += 60 * 60_000; advanceTimeBy(30.seconds)
        assertEquals(emptyList(), reads) // nothing read automatically, even when stale

        svc.refresh("A") // explicit refresh still works
        assertEquals(listOf("A"), reads)

        svc.setPollInterval(5) // switching back applies on the next pass
        now += 6 * 60_000; advanceTimeBy(30.seconds)
        assertEquals(listOf("A", "A"), reads)
        assertEquals(5, StateStore(stateFile).load().settings.pollIntervalMinutes) // persisted
        job.cancel()
    }

    @Test
    fun picksUpExternalEditsToFavorites() = runTest {
        val svc = service(listOf(Favorite("A", "Old name")), { reading(50) })
        svc.refresh("A")
        Thread.sleep(10) // ensure a newer mtime
        val edited = StateStore(stateFile)
        edited.save(StoredState(favorites = listOf(Favorite("A", "New name"), Favorite("B", "Added"))))
        stateFile.setLastModified(System.currentTimeMillis() + 5_000)

        svc.reloadIfChangedExternally()
        assertEquals(listOf("New name", "Added"), svc.state.value.favorites.map { it.nickname })
        assertEquals(50, svc.state.value.readings["A"]!!.stateOfChargePct) // our reading survives
        assertEquals(1, svc.state.value.recent["A"]!!.size) // and so do its rate samples
    }

    @Test
    fun estimatesFollowReadingsAndThresholdsAreSetThroughTheApi() = testApplication {
        // Discharging at 10 A: 100 Ah battery loses 5 Ah every 30 min.
        var soc = 60
        val svc = service(listOf(Favorite("A5:C2:39:57:73:E3", "Garage", groupId = "g"))) {
            reading(soc).copy(currentMa = -10_000)
        }
        svc.refresh("A5:C2:39:57:73:E3")
        now += 30 * 60_000; soc = 55
        svc.refresh("A5:C2:39:57:73:E3")
        application { hubModule(svc, history, CoroutineScope(Dispatchers.Default)) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "localhost:8640") } }
        suspend fun put(path: String, body: String) = client.request(path) {
            method = HttpMethod.Put
            header("X-Battery-Hub", "1")
            contentType(ContentType.Application.Json); setBody(body)
        }

        assertEquals(HttpStatusCode.NoContent, put("/api/favorites/A5:C2:39:57:73:E3/thresholds", """{"dischargeToPct":20}""").status)
        assertEquals(HttpStatusCode.NoContent, put("/api/groups/g/thresholds", """{"dischargeToPct":50,"chargeToPct":90}""").status)
        assertEquals(HttpStatusCode.NoContent, put("/api/thresholds", """{"dischargeToPct":10}""").status)
        assertEquals(HttpStatusCode.BadRequest, put("/api/thresholds", """{"dischargeToPct":90,"chargeToPct":80}""").status)
        assertEquals(HttpStatusCode.NotFound, put("/api/groups/nope/thresholds", "{}").status)

        val o = json.decodeFromString<OverviewDto>(client.get("/api/overview").bodyAsText())
        val e = o.batteries.getValue("A5:C2:39:57:73:E3").estimate!!
        assertEquals(-10_000, e.rateMa)
        assertEquals(20, e.targetPct)
        assertEquals(35 * 360_000L, e.durationMs) // 35 Ah at 10 A
        assertTrue(e.confident)
        assertEquals(5 * 360_000L, o.groups.single().estimate!!.durationMs) // 5 Ah to the group's 50%
        assertEquals(45 * 360_000L, o.totalEstimate!!.durationMs) // to 10%
        assertEquals(10, o.totalThresholds.low)
        assertEquals(90, StateStore(stateFile).load().groups.single().thresholds.high) // saved
    }

    @Test
    fun apiServesOverviewAndRejectsUnknownBatteries() = testApplication {
        val svc = service(listOf(Favorite("A", "Garage", groupId = "g"), Favorite("B", "Shed")), { reading(70) })
        svc.refresh("A")
        application { hubModule(svc, history, CoroutineScope(Dispatchers.Default)) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "localhost:8640") } }

        val overview = Json { ignoreUnknownKeys = true }.decodeFromString<OverviewDto>(client.get("/api/overview").bodyAsText())
        assertEquals(listOf("A"), overview.groups.single().members)
        assertEquals(listOf("B"), overview.ungrouped)
        assertEquals(70, overview.total!!.stateOfChargePct)
        assertEquals(1, overview.total!!.reportingCount)

        assertEquals(HttpStatusCode.NotFound, client.post("/api/batteries/ZZ/refresh") { header("X-Battery-Hub", "1") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/batteries/ZZ/history").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/batteries/A/history").status)
        assertTrue(client.get("/").bodyAsText().contains("<title>Battery Hub</title>"))
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun guardsRejectCrossSiteAndRebindingRequests() = testApplication {
        val svc = service(listOf(Favorite("A5:C2:39:57:73:E3", "Garage")), { reading(70) })
        application { hubModule(svc, history, CoroutineScope(Dispatchers.Default)) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "localhost:8640") } }

        // No custom header: what a form post or fetch from another website looks like.
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/favorites/A5:C2:39:57:73:E3").status)
        // DNS rebinding: right address, wrong Host.
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/overview") { header(HttpHeaders.Host, "evil.example") }.status)
        // Reads from the dashboard itself still work.
        assertEquals(HttpStatusCode.OK, client.get("/api/overview").status)
        assertEquals(1, svc.state.value.favorites.size)
    }

    @Test
    fun editsFavoritesAndGroupsThroughTheApi() = testApplication {
        val svc = service(listOf(Favorite("A5:C2:39:57:73:E3", "Garage")), { reading(70) })
        application { hubModule(svc, history, CoroutineScope(Dispatchers.Default)) }
        val client = createClient { defaultRequest { header(HttpHeaders.Host, "localhost:8640") } }
        suspend fun send(method: HttpMethod, path: String, body: String? = null) = client.request(path) {
            this.method = method
            header("X-Battery-Hub", "1")
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }

        assertEquals(HttpStatusCode.Created, send(HttpMethod.Post, "/api/favorites", """{"mac":"a5:c2:39:57:73:f1","nickname":" Shed "}""").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/favorites", """{"mac":"nope","nickname":"x"}""").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/favorites", """{"mac":"A5:C2:39:57:73:00","nickname":"  "}""").status)
        assertEquals(listOf("Garage", "Shed"), svc.state.value.favorites.map { it.nickname })

        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/favorites/A5:C2:39:57:73:E3/nickname", """{"nickname":"Garage left"}""").status)
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Put, "/api/favorites/A5:C2:00:00:00:00/nickname", """{"nickname":"x"}""").status)

        val created = send(HttpMethod.Post, "/api/groups", """{"name":"House","wiring":"SERIES"}""")
        assertEquals(HttpStatusCode.Created, created.status)
        val id = json.decodeFromString<Map<String, String>>(created.bodyAsText()).getValue("id")
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/favorites/A5:C2:39:57:73:F1/group", """{"groupId":"$id"}""").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Put, "/api/favorites/A5:C2:39:57:73:F1/group", """{"groupId":"missing"}""").status)
        assertEquals(id, svc.state.value.favorites[1].groupId)

        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Patch, "/api/groups/$id", """{"wiring":"PARALLEL"}""").status)
        assertEquals(Wiring.PARALLEL, svc.state.value.groups.first { it.id == id }.wiring)

        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Post, "/api/favorites/A5:C2:39:57:73:F1/move", """{"delta":-1}""").status)
        assertEquals(listOf("Shed", "Garage left"), svc.state.value.favorites.map { it.nickname })

        assertEquals(HttpStatusCode.OK, send(HttpMethod.Put, "/api/settings", """{"pollIntervalMinutes":0}""").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Put, "/api/settings", """{"pollIntervalMinutes":5000}""").status)
        assertEquals(true, svc.state.value.settings.isManual)

        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/groups/$id").status)
        assertEquals(null, svc.state.value.favorites[0].groupId)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/favorites/A5:C2:39:57:73:F1").status)
        assertEquals(listOf("Garage left"), svc.state.value.favorites.map { it.nickname })

        // Saved to disk.
        assertEquals(listOf("Garage left"), StateStore(stateFile).load().favorites.map { it.nickname })
    }

    @Test
    fun scanHidesFavoritesAndRejectsOverlap() = runTest {
        StateStore(stateFile).save(StoredState(favorites = listOf(Favorite("A5:C2:39:57:73:E3", "Garage"))))
        val svc = HubService(
            StateStore(stateFile), history, { reading(50) },
            scanner = {
                listOf(
                    DiscoveredDevice("A5:C2:39:57:73:E3", "DP04S007L4S100A", -60),
                    DiscoveredDevice("A5:C2:39:57:78:44", "DP04S007L4S100A", -70),
                )
            },
            clock = { now },
        )
        assertTrue(svc.runScan(1.seconds))
        assertEquals(listOf("A5:C2:39:57:78:44"), svc.scanResults().devices.map { it.mac })

        // Adding a discovered battery keeps its advertised name.
        svc.addFavorite("A5:C2:39:57:78:44", "Camper")
        assertEquals("DP04S007L4S100A", svc.state.value.favorites.last().bleName)
        assertEquals(emptyList(), svc.scanResults().devices)
    }
}
