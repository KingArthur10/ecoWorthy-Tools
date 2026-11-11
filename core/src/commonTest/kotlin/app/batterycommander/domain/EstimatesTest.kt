package app.batterycommander.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EstimatesTest {
    private val min = 60_000L
    private val hour = 60 * min

    private fun reading(t: Long, remainingMah: Int, currentMa: Int, capacityMah: Int = 100_000) = BatteryReading(
        timestampMs = t,
        packVoltageMv = 13_200,
        currentMa = currentMa,
        stateOfChargePct = remainingMah * 100 / capacityMah,
        remainingCapacityMah = remainingMah,
        nominalCapacityMah = capacityMah,
        cycles = 0,
        cellVoltagesMv = List(4) { 3300 },
        temperaturesDeciC = listOf(200),
        chargeFetOn = true,
        dischargeFetOn = true,
        balancingMask = 0,
        protections = emptyList(),
        productionDate = "2026-07-17",
        firmwareVersion = "6.6",
        model = "DP04S007L4S100A",
    )

    @Test
    fun rateComesFromRemainingCapacityAcrossTheWindow() {
        // A fridge: the latest snapshot says 0.3 A, but 2.5 Ah left over 30 min is 5 A on average.
        val samples = listOf(RateSample(0, 50_000, -8_000), RateSample(10 * min, 49_000, -300), RateSample(20 * min, 48_200, -6_000))
        val rate = rollingRate(reading(30 * min, 47_500, -300), samples)
        assertEquals(-5_000, rate.currentMa)
        assertEquals(30 * min, rate.spanMs)
        assertTrue(rate.confident)
    }

    @Test
    fun usesTheOldestSampleInsideTheWindowOnly() {
        val samples = listOf(RateSample(0, 90_000, -20_000), RateSample(40 * min, 60_000, -2_000))
        val rate = rollingRate(reading(60 * min, 59_000, -2_000), samples)
        assertEquals(-3_000, rate.currentMa) // 1 Ah over 20 min, ignoring the heavy load an hour ago
        assertEquals(20 * min, rate.spanMs)
    }

    @Test
    fun slowPollingReachesBackForTheLatestEarlierSample() {
        val samples = listOf(RateSample(0, 80_000, -1_000), RateSample(hour, 79_000, -1_000))
        val rate = rollingRate(reading(2 * hour, 78_000, -1_000), samples)
        assertEquals(-1_000, rate.currentMa)
        assertEquals(hour, rate.spanMs)
    }

    @Test
    fun aSingleReadingFallsBackToItsCurrentAsRough() {
        val rate = rollingRate(reading(0, 50_000, -4_000), emptyList())
        assertEquals(Rate(-4_000, 0, confident = false), rate)
        // Too close together to resolve, and too old to use, count as no history too.
        assertEquals(0, rollingRate(reading(4 * hour, 50_000, -4_000), listOf(RateSample(0, 60_000, 0), RateSample(4 * hour - 30_000, 50_010, 0))).spanMs)
    }

    @Test
    fun aDirectionChangeUsesTheLatestCurrent() {
        // Discharging all morning, then the sun comes up.
        val samples = listOf(RateSample(0, 50_000, -3_000))
        val rate = rollingRate(reading(30 * min, 49_000, 15_000), samples)
        assertEquals(15_000, rate.currentMa)
        assertFalse(rate.confident)
    }

    @Test
    fun aBigLoadChangeMakesItRough() {
        val samples = listOf(RateSample(0, 50_000, -1_000))
        val rate = rollingRate(reading(30 * min, 49_500, -40_000), samples)
        assertEquals(-1_000, rate.currentMa)
        assertFalse(rate.confident)
    }

    @Test
    fun batteryEstimateCountsToItsThreshold() {
        val r = reading(1_000, 60_000, -10_000)
        val e = estimate(r, Rate(-10_000, 30 * min, true), socThresholds(20, null))
        assertEquals(PowerFlow.DISCHARGING, e.flow)
        assertEquals(20, e.targetPct)
        assertEquals(4 * hour, e.durationMs) // 40 Ah at 10 A
        assertEquals(1_000 + 4 * hour, e.etaMs)

        val c = estimate(r, Rate(20_000, 30 * min, true), SocThresholds())
        assertEquals(100, c.targetPct)
        assertEquals(2 * hour, c.durationMs) // 40 Ah at 20 A

        assertEquals(0, estimate(r, Rate(20_000, 30 * min, true), socThresholds(null, 60)).durationMs) // already past 60%
        val idle = estimate(r, Rate(50, 30 * min, true), SocThresholds())
        assertEquals(PowerFlow.IDLE, idle.flow)
        assertNull(idle.durationMs)
    }

    @Test
    fun parallelGroupPoolsAmpHoursAndCurrents() {
        val a = reading(1_000, 50_000, -5_000) to Rate(-5_000, 30 * min, true)
        val b = reading(2_000, 132_000, -7_000, capacityMah = 165_000) to Rate(-7_000, 20 * min, true)
        val e = estimateGroup(listOf(a, b), Wiring.PARALLEL, socThresholds(10, null), 2)!!
        // 182 Ah left, stop at 26.5 Ah, at 12 A.
        assertEquals((182_000L - 26_500) * 3_600_000 / 12_000, e.durationMs)
        assertEquals(-12_000, e.rateMa)
        assertEquals(1_000, e.asOfMs)
        assertEquals(20 * min, e.spanMs)
        assertTrue(e.confident)
        assertFalse(estimateGroup(listOf(a, b), Wiring.PARALLEL, SocThresholds(), 3)!!.confident) // one has no reading
    }

    @Test
    fun seriesStringIsLimitedByItsWeakestMember() {
        val low = reading(0, 30_000, -10_000) to Rate(-10_000, 30 * min, true)
        val high = reading(0, 80_000, -10_000) to Rate(-10_000, 30 * min, true)
        val discharge = estimateGroup(listOf(low, high), Wiring.SERIES, socThresholds(20, null), 2)!!
        assertEquals(hour, discharge.durationMs) // the 30% battery hits 20% first

        val lowC = low.first to Rate(10_000, 30 * min, true)
        val highC = high.first to Rate(10_000, 30 * min, true)
        val charge = estimateGroup(listOf(lowC, highC), Wiring.SERIES, socThresholds(null, 90), 2)!!
        assertEquals(6 * hour, charge.durationMs) // until the 30% battery reaches 90%
    }

    @Test
    fun thresholdsAreValidatedAndDefaultsStoredAsNull() {
        assertEquals(SocThresholds(), socThresholds(0, 100))
        assertEquals(SocThresholds(20, 80), socThresholds(20, 80))
        assertFailsWith<IllegalArgumentException> { socThresholds(80, 20) }
        assertFailsWith<IllegalArgumentException> { socThresholds(null, 101) }
        assertFailsWith<IllegalArgumentException> { socThresholds(-1, null) }
    }

    @Test
    fun describeCountsDownFromNow() {
        val e = TimeEstimate(PowerFlow.DISCHARGING, 20, 4 * hour, -10_000, 30 * min, asOfMs = 0, confident = true)
        assertEquals("Down to 20% in 4 h", e.describe(0))
        assertEquals("Down to 20% in 3 h 30 min", e.describe(30 * min))
        assertEquals("Down to 20% by now, if the rate held", e.describe(5 * hour))
        assertEquals("Full in 45 min", e.copy(flow = PowerFlow.CHARGING, targetPct = 100, durationMs = 45 * min).describe(0))
        assertEquals("At or below 20%", e.copy(durationMs = 0).describe(0))
        assertEquals("Empty", e.copy(targetPct = 0, durationMs = 0).describe(0))
        assertNull(e.copy(flow = PowerFlow.IDLE, targetPct = null, durationMs = null).describe(0))
        assertEquals("2 d 4 h", formatDuration(52 * hour))
        assertEquals("Based on the latest reading only", e.copy(spanMs = 0).basis())
    }

    @Test
    fun readingsFeedTheSamplesAndSummaryUsesThem() {
        val fav = Favorite("A5:C2:39:57:73:E3", "Van", thresholds = socThresholds(null, 80))
        var s = StoredState(favorites = listOf(fav))
        s = s.withReading(fav.mac, reading(0, 58_300, 20_100))
        s = s.withReading(fav.mac, reading(10 * min, 61_790, 20_190)) // real capture, 2026-11-10
        assertEquals(2, s.recent[fav.mac]!!.size)
        val e = s.summarize().estimates[fav.mac]!!
        assertEquals(20_940, e.rateMa)
        assertEquals(80, e.targetPct)
        assertTrue(e.confident)
        assertEquals((80_000L - 61_790) * 3_600_000 / 20_940, e.durationMs)

        // Old samples age out; removing the favorite drops them.
        s = s.withReading(fav.mac, reading(4 * hour, 70_000, 0))
        assertEquals(1, s.recent[fav.mac]!!.size)
        assertNull(s.removeFavorite(fav.mac).recent[fav.mac])
    }
}
