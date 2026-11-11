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
import io.ktor.client.request.get
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
        return HubService(StateStore(stateFile), history, reader, pollInterval = 10.minutes, clock = { now })
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
    }

    @Test
    fun apiServesOverviewAndRejectsUnknownBatteries() = testApplication {
        val svc = service(listOf(Favorite("A", "Garage", groupId = "g"), Favorite("B", "Shed")), { reading(70) })
        svc.refresh("A")
        application { hubModule(svc, history, CoroutineScope(Dispatchers.Default)) }

        val overview = Json { ignoreUnknownKeys = true }.decodeFromString<OverviewDto>(client.get("/api/overview").bodyAsText())
        assertEquals(listOf("A"), overview.groups.single().members)
        assertEquals(listOf("B"), overview.ungrouped)
        assertEquals(70, overview.total!!.stateOfChargePct)
        assertEquals(1, overview.total!!.reportingCount)

        assertEquals(HttpStatusCode.NotFound, client.post("/api/batteries/ZZ/refresh").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/batteries/ZZ/history").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/batteries/A/history").status)
        assertTrue(client.get("/").bodyAsText().contains("<title>Battery Hub</title>"))
    }
}
