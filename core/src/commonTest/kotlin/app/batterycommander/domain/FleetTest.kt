package app.batterycommander.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FleetTest {
    private fun reading(soc: Int) = BatteryReading(
        timestampMs = 0, packVoltageMv = 13_200, currentMa = 0, stateOfChargePct = soc,
        remainingCapacityMah = 1000 * soc, nominalCapacityMah = 100_000, cycles = 0,
        cellVoltagesMv = List(4) { 3300 }, temperaturesDeciC = emptyList(), chargeFetOn = true,
        dischargeFetOn = true, balancingMask = 0, protections = emptyList(), productionDate = "",
        firmwareVersion = "", model = "",
    )

    private val state = StoredState(
        favorites = listOf(
            Favorite("A", "a", groupId = "g1"),
            Favorite("B", "b", groupId = "g1"),
            Favorite("C", "c"),
            Favorite("D", "d", groupId = "deleted-group"),
        ),
        readings = mapOf("A" to reading(50), "B" to reading(70), "C" to reading(10)),
        groups = listOf(BatteryGroup("g1", "House", Wiring.PARALLEL), BatteryGroup("g2", "Empty")),
    )

    @Test
    fun summarizesGroupsAndTotals() {
        val s = state.summarize()
        assertEquals(listOf("A", "B"), s.groups[0].members.map { it.mac })
        assertEquals(60, s.groups[0].stats!!.stateOfChargePct)
        assertNull(s.groups[1].stats)
        // Ungrouped includes batteries whose group no longer exists.
        assertEquals(listOf("C", "D"), s.ungrouped.map { it.mac })
        assertEquals(4, s.total!!.batteryCount)
        assertEquals(3, s.total!!.reportingCount)
    }

    @Test
    fun pollIntervalDefaultsValidatesAndLabels() {
        assertEquals(DEFAULT_POLL_MINUTES, StoredState().settings.pollIntervalMinutes)
        assertEquals(true, StoredState().withPollInterval(MANUAL_REFRESH).settings.isManual)
        assertEquals(30, StoredState().withPollInterval(30).settings.pollIntervalMinutes)
        kotlin.test.assertFailsWith<IllegalArgumentException> { StoredState().withPollInterval(-1) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { StoredState().withPollInterval(1441) }
        assertEquals(
            listOf("Manual only", "Every 1 min", "Every 2 min", "Every 5 min", "Every 10 min", "Every 15 min", "Every 30 min", "Every hour", "Every 2 hours"),
            POLL_INTERVAL_CHOICES.map(::pollIntervalLabel),
        )
    }

    @Test
    fun readsPhoneFormat() {
        // As written by the Android app (pretty-printed, defaults omitted).
        val json = """{"favorites":[{"mac":"A5:C2:39:57:78:44","nickname":"Close","bleName":"DP04S007L4S100A"}]}"""
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<StoredState>(json)
        assertEquals("Close", parsed.favorites.single().nickname)
        assertEquals(DEFAULT_POLL_MINUTES, parsed.settings.pollIntervalMinutes) // older files have no settings
    }
}
