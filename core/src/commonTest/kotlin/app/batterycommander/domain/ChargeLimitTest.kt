package app.batterycommander.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChargeLimitTest {
    private fun reading(soc: Int, chargeOn: Boolean = true, currentMa: Int = 20_000, protections: List<String> = emptyList()) =
        BatteryReading(
            timestampMs = 0, packVoltageMv = 13_400, currentMa = currentMa, stateOfChargePct = soc,
            remainingCapacityMah = soc * 1000, nominalCapacityMah = 100_000, cycles = 0, cellVoltagesMv = List(4) { 3350 },
            temperaturesDeciC = listOf(200), chargeFetOn = chargeOn, dischargeFetOn = true, balancingMask = 0,
            protections = if (!chargeOn && protections.isEmpty()) listOf(SOFTWARE_LOCK) else protections,
            productionDate = "2026-07-17", firmwareVersion = "6.6", model = "DP04S007L4S100A",
        )

    @Test
    fun turnsOffAtTheLimitAndBackOnBelowTheGap() {
        assertNull(chargeLimitAction(reading(69), 70, rearmed = false))
        assertEquals(ChargeAction.TURN_OFF, chargeLimitAction(reading(70), 70, rearmed = false))
        assertEquals(ChargeAction.TURN_OFF, chargeLimitAction(reading(72, currentMa = 0), 70, rearmed = false))
        assertNull(chargeLimitAction(reading(70, chargeOn = false, currentMa = 0), 70, rearmed = false))
        assertNull(chargeLimitAction(reading(66, chargeOn = false, currentMa = 0), 70, rearmed = false)) // inside the gap
        assertEquals(ChargeAction.TURN_ON, chargeLimitAction(reading(65, chargeOn = false, currentMa = 0), 70, rearmed = false))
    }

    @Test
    fun raisingOrClearingTheLimitResumesRightAway() {
        val held = reading(71, chargeOn = false, currentMa = 0)
        assertNull(chargeLimitAction(held, 74, rearmed = false))
        assertEquals(ChargeAction.TURN_ON, chargeLimitAction(held, 74, rearmed = true))
        assertEquals(ChargeAction.TURN_ON, chargeLimitAction(held, 100, rearmed = false))
        assertEquals(ChargeAction.TURN_ON, chargeLimitAction(held, null, rearmed = true)) // limit turned off
        assertNull(chargeLimitAction(held, null, rearmed = false)) // someone else's manual "charge off" is left alone
    }

    @Test
    fun neverFightsTheBms() {
        // Charge switch off without the lock flag: the BMS turned it off (e.g. full).
        assertNull(chargeLimitAction(reading(60, chargeOn = false, protections = listOf("CELL_OVERVOLTAGE")), 70, rearmed = true))
        assertNull(chargeLimitAction(reading(80, protections = listOf("CHARGE_OVERTEMPERATURE")), 70, rearmed = false))
    }

    @Test
    fun pollsFasterWhenChargingNearTheLimit() {
        val tenMin = 600_000L
        assertEquals(NEAR_LIMIT_POLL_MS, pollIntervalMs(tenMin, reading(66), 70))
        assertEquals(tenMin, pollIntervalMs(tenMin, reading(60), 70))
        assertEquals(tenMin, pollIntervalMs(tenMin, reading(68, currentMa = -5_000), 70))
        assertEquals(tenMin, pollIntervalMs(tenMin, reading(68), null))
    }

    @Test
    fun seriesMembersCantEnableIt() {
        val s = StoredState(
            favorites = listOf(Favorite("A", "a", groupId = "s"), Favorite("B", "b")),
            groups = listOf(BatteryGroup("s", "String", Wiring.SERIES)),
        )
        assertFailsWith<IllegalArgumentException> { s.setChargeLimit("A", true) }
        assertEquals(true, s.setChargeLimit("B", true).favorites[1].chargeLimit)
        assertEquals(70, s.setChargeLimit("B", true).setFavoriteThresholds("B", socThresholds(null, 70)).favorites[1].enforcedLimitPct)
    }
}
