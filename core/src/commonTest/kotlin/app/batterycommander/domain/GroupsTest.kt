package app.batterycommander.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupsTest {
    private fun reading(
        socPct: Int,
        capacityMah: Int,
        voltageMv: Int = 13_200,
        currentMa: Int = 0,
        timestampMs: Long = 1_000,
    ) = BatteryReading(
        timestampMs = timestampMs,
        packVoltageMv = voltageMv,
        currentMa = currentMa,
        stateOfChargePct = socPct,
        remainingCapacityMah = capacityMah * socPct / 100,
        nominalCapacityMah = capacityMah,
        cycles = 0,
        cellVoltagesMv = List(4) { voltageMv / 4 },
        temperaturesDeciC = listOf(200),
        chargeFetOn = true,
        dischargeFetOn = true,
        balancingMask = 0,
        protections = emptyList(),
        productionDate = "2026-07-17",
        firmwareVersion = "6.6",
        model = "DP04S007L4S100A",
    )

    private val small = reading(socPct = 30, capacityMah = 100_000, currentMa = -5_000)
    private val big = reading(socPct = 80, capacityMah = 165_000, voltageMv = 13_400, currentMa = -7_000, timestampMs = 500)

    @Test
    fun parallelSumsAmpHoursAndWeightsSoc() {
        val s = aggregate(listOf(small, big), Wiring.PARALLEL)!!
        assertEquals(265_000, s.capacityMah)
        assertEquals(30_000 + 132_000, s.remainingMah)
        assertEquals(61, s.stateOfChargePct) // 162 / 265 = 61.1%
        assertEquals(13_300, s.voltageMv)
        assertEquals(-12_000, s.currentMa)
    }

    @Test
    fun seriesTakesWeakestMemberAndSumsVoltage() {
        val s = aggregate(listOf(small, big), Wiring.SERIES)!!
        assertEquals(100_000, s.capacityMah)
        assertEquals(30_000, s.remainingMah)
        assertEquals(30, s.stateOfChargePct)
        assertEquals(26_600, s.voltageMv)
    }

    @Test
    fun energyAlwaysSumsRegardlessOfWiring() {
        // 100 Ah × 12.8 V + 165 Ah × 12.8 V = 3392 Wh
        for (w in Wiring.entries) {
            val s = aggregate(listOf(small, big), w)!!
            assertEquals(3_392_000, s.energyCapacityMwh, "wiring $w")
            assertEquals((30_000L + 132_000) * 12_800 / 1000, s.energyRemainingMwh, "wiring $w")
        }
    }

    @Test
    fun unwiredGroupHasNoCombinedVoltageOrCurrent() {
        val s = aggregate(listOf(small, big), Wiring.NONE)!!
        assertNull(s.voltageMv)
        assertNull(s.currentMa)
        assertEquals(61, s.stateOfChargePct)
    }

    @Test
    fun powerSumsMembers() {
        val s = aggregate(listOf(small, big), Wiring.NONE)!!
        assertEquals(small.powerMw + big.powerMw, s.powerMw)
    }

    @Test
    fun missingReadingsMakeTotalsPartial() {
        val s = aggregate(listOf(small, null, big), Wiring.PARALLEL)!!
        assertEquals(3, s.batteryCount)
        assertEquals(2, s.reportingCount)
        assertTrue(s.isPartial)
        assertEquals(500, s.oldestReadingMs)
    }

    @Test
    fun noReadingsGivesNull() {
        assertNull(aggregate(listOf(null, null), Wiring.PARALLEL))
        assertNull(aggregate(emptyList(), Wiring.PARALLEL))
    }

    @Test
    fun prefersFullChargeCapacity() {
        val learned = small.copy(fullChargeCapacityMah = 95_000)
        assertEquals(95_000, aggregate(listOf(learned), Wiring.PARALLEL)!!.capacityMah)
    }
}
