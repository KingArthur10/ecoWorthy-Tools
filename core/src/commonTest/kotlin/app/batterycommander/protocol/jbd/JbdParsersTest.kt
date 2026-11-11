package app.batterycommander.protocol.jbd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Expected values come from the independent Python probe decode (tools/jbd_probe.py). */
class JbdParsersTest {
    private data class Expected(
        val mac: String,
        val voltageMv: Int,
        val currentMa: Int,
        val remainingMah: Int,
        val nominalMah: Int,
        val cycles: Int,
        val date: String,
        val soc: Int,
        val tempDeciC: Int,
        val cellsMv: List<Int>,
        val model: String,
    )

    private val expected = listOf(
        Expected("A5:C2:39:57:73:E3", 13130, 0, 31230, 100000, 0, "2026-07-17", 31, 174, listOf(3283, 3284, 3282, 3285), "DP04S007L4S100A"),
        Expected("A5:C2:39:57:73:F1", 13550, 19990, 58330, 100000, 0, "2026-07-17", 58, 224, listOf(3388, 3389, 3387, 3388), "DP04S007L4S100A"),
        Expected("A5:C2:39:57:78:44", 13270, 0, 68900, 100000, 0, "2026-07-18", 69, 173, listOf(3318, 3319, 3319, 3318), "DP04S007L4S100A"),
        Expected("A5:C2:39:57:8B:35", 13130, 0, 32250, 100000, 0, "2026-07-17", 32, 168, listOf(3284, 3284, 3283, 3285), "DP04S007L4S100A"),
        Expected("A5:C2:37:2E:A2:52", 13340, 0, 133280, 165000, 9, "2024-08-26", 81, 175, listOf(3335, 3336, 3336, 3336), "DP04S007L4S120A"),
    )

    @Test
    fun decodesBasicInfoFromEveryCapturedBattery() {
        for (e in expected) {
            val info = JbdParsers.basicInfo(JbdFrame.parseResponse(Captures.byMac(e.mac).basic.hexToBytes()).payload)
            val msg = "battery ${e.mac}"
            assertEquals(e.voltageMv, info.packVoltageMv, msg)
            assertEquals(e.currentMa, info.currentMa, msg)
            assertEquals(e.remainingMah, info.remainingCapacityMah, msg)
            assertEquals(e.nominalMah, info.nominalCapacityMah, msg)
            assertEquals(e.cycles, info.cycles, msg)
            assertEquals(e.date, info.productionDate.toString(), msg)
            assertEquals(e.soc, info.stateOfChargePct, msg)
            assertEquals(listOf(e.tempDeciC), info.temperaturesDeciC, msg)
            assertEquals("6.6", info.softwareVersion, msg)
            assertEquals(4, info.cellCount, msg)
            assertTrue(info.chargeFetOn && info.dischargeFetOn, msg)
            assertTrue(info.protections.isEmpty(), msg)
            assertEquals(0L, info.balancingMask, msg)
            assertEquals(e.nominalMah, info.fullChargeCapacityMah, msg)
        }
    }

    @Test
    fun decodesCellVoltagesAndModel() {
        for (e in expected) {
            val c = Captures.byMac(e.mac)
            assertEquals(e.cellsMv, JbdParsers.cellVoltages(JbdFrame.parseResponse(c.cells.hexToBytes()).payload))
            assertEquals(e.model, JbdParsers.hardwareVersion(JbdFrame.parseResponse(c.hwver.hexToBytes()).payload))
        }
    }

    @Test
    fun chargingCurrentIsPositiveAndPowerIsDerived() {
        val info = JbdParsers.basicInfo(JbdFrame.parseResponse(Captures.byMac("A5:C2:39:57:73:F1").basic.hexToBytes()).payload)
        assertEquals(19990, info.currentMa)
        assertEquals(13550L * 19990 / 1000, info.powerMw)
    }

    @Test
    fun negativeCurrentMeansDischarging() {
        val payload = basicPayload().also { it[2] = 0xFF.toByte(); it[3] = 0x9C.toByte() } // -100 × 10 mA
        assertEquals(-1000, JbdParsers.basicInfo(payload).currentMa)
    }

    @Test
    fun decodesFetAndProtectionBits() {
        val payload = basicPayload().also {
            it[16] = 0x05; it[17] = 0x01 // bits 0, 8, 10
            it[20] = 0x02 // charge off, discharge on
        }
        val info = JbdParsers.basicInfo(payload)
        assertEquals(
            setOf(JbdProtection.CELL_OVERVOLTAGE, JbdProtection.CHARGE_OVERCURRENT, JbdProtection.SHORT_CIRCUIT),
            info.protections,
        )
        assertEquals(false, info.chargeFetOn)
        assertEquals(true, info.dischargeFetOn)
    }

    @Test
    fun extendedBlockIsOptional() {
        val payload = basicPayload().copyOfRange(0, 25) // fixed part + one temperature only
        assertEquals(null, JbdParsers.basicInfo(payload).fullChargeCapacityMah)
    }

    @Test
    fun rejectsTruncatedPayloads() {
        assertFailsWith<JbdProtocolException> { JbdParsers.basicInfo(ByteArray(10)) }
        // Declares 3 temperature sensors but carries only one.
        assertFailsWith<JbdProtocolException> {
            JbdParsers.basicInfo(basicPayload().copyOfRange(0, 25).also { it[22] = 3 })
        }
        assertFailsWith<JbdProtocolException> { JbdParsers.cellVoltages(ByteArray(3)) }
    }

    private fun basicPayload(): ByteArray =
        JbdFrame.parseResponse(Captures.byMac("A5:C2:39:57:73:E3").basic.hexToBytes()).payload
}
