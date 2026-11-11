package app.batterycommander.domain

import app.batterycommander.protocol.jbd.Captures
import app.batterycommander.protocol.jbd.JbdFrame
import app.batterycommander.protocol.jbd.JbdParsers
import app.batterycommander.protocol.jbd.JbdSnapshot
import app.batterycommander.protocol.jbd.hexToBytes
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class BatteryTest {
    private fun snapshot(mac: String): JbdSnapshot {
        val c = Captures.byMac(mac)
        return JbdSnapshot(
            basic = JbdParsers.basicInfo(JbdFrame.parseResponse(c.basic.hexToBytes()).payload),
            cellVoltagesMv = JbdParsers.cellVoltages(JbdFrame.parseResponse(c.cells.hexToBytes()).payload),
            model = JbdParsers.hardwareVersion(JbdFrame.parseResponse(c.hwver.hexToBytes()).payload),
        )
    }

    @Test
    fun mapsSnapshotToReading() {
        val r = snapshot("A5:C2:37:2E:A2:52").toReading(timestampMs = 1000, rssiDbm = -93)
        assertEquals(81, r.stateOfChargePct)
        assertEquals(165000, r.nominalCapacityMah)
        assertEquals(1, r.cellDeltaMv)
        assertEquals("2024-08-26", r.productionDate)
        assertEquals(-93, r.rssiDbm)
        assertEquals(PowerFlow.IDLE, r.flow)
    }

    @Test
    fun chargingBatteryReportsCharging() {
        assertEquals(PowerFlow.CHARGING, snapshot("A5:C2:39:57:73:F1").toReading(0, null).flow)
    }

    @Test
    fun readingSurvivesJsonRoundTrip() {
        val r = snapshot("A5:C2:39:57:73:E3").toReading(42, -80)
        assertEquals(r, Json.decodeFromString<BatteryReading>(Json.encodeToString(r)))
    }

    @Test
    fun signalBarsBuckets() {
        assertEquals(listOf(4, 3, 2, 1, 0, 0), listOf(-59, -70, -83, -93, -100, null).map(::signalBars))
    }

    @Test
    fun formatsAge() {
        assertEquals("just now", formatAge(59_000, 0))
        assertEquals("5m ago", formatAge(300_000, 0))
        assertEquals("3h ago", formatAge(3 * 3_600_000L, 0))
        assertEquals("2d ago", formatAge(2 * 86_400_000L, 0))
        assertEquals("just now", formatAge(0, 5_000)) // clock skew
    }
}
