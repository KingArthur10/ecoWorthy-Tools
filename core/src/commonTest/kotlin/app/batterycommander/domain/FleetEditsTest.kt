package app.batterycommander.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class FleetEditsTest {
    private val base = StoredState()
        .addFavorite("a5:c2:39:57:73:e3", " Garage ")
        .addFavorite("A5:C2:39:57:73:F1", "Shed")
        .addFavorite("A5:C2:39:57:78:44", "Camper")

    private val macs get() = base.favorites.map { it.mac }

    @Test
    fun addNormalizesAndTrims() {
        assertEquals("A5:C2:39:57:73:E3", base.favorites[0].mac)
        assertEquals("Garage", base.favorites[0].nickname)
    }

    @Test
    fun addingAnExistingFavoriteRenamesIt() {
        val s = base.addFavorite("A5:C2:39:57:73:F1", "Shed 2")
        assertEquals(3, s.favorites.size)
        assertEquals("Shed 2", s.favorites[1].nickname)
    }

    @Test
    fun rejectsBadInput() {
        assertFailsWith<IllegalArgumentException> { base.addFavorite("not-a-mac", "x") }
        assertFailsWith<IllegalArgumentException> { base.addFavorite("A5:C2:39:57:73:00", "   ") }
        assertFailsWith<IllegalArgumentException> { base.renameFavorite(macs[0], "x".repeat(MAX_NAME_LENGTH + 1)) }
        assertFailsWith<IllegalArgumentException> { base.assignGroup(macs[0], "missing") }
    }

    @Test
    fun movesWithinBounds() {
        assertEquals(listOf(macs[1], macs[0], macs[2]), base.moveFavorite(macs[1], -1).favorites.map { it.mac })
        assertEquals(listOf(macs[1], macs[2], macs[0]), base.moveFavorite(macs[0], +5).favorites.map { it.mac })
        assertSame(base, base.moveFavorite(macs[0], -1))
        assertSame(base, base.moveFavorite("A5:C2:00:00:00:00", 1))
    }

    @Test
    fun groupLifecycle() {
        var s = base.createGroup("g1", "House", Wiring.PARALLEL)
            .assignGroup(macs[0], "g1")
            .assignGroup(macs[1], "g1")
        assertEquals(2, s.favorites.count { it.groupId == "g1" })

        s = s.updateGroup("g1", wiring = Wiring.SERIES)
        assertEquals(BatteryGroup("g1", "House", Wiring.SERIES), s.groups.single())
        s = s.updateGroup("g1", name = "Camper bank")
        assertEquals("Camper bank", s.groups.single().name)

        s = s.assignGroup(macs[1], null)
        assertNull(s.favorites[1].groupId)

        s = s.deleteGroup("g1")
        assertEquals(emptyList(), s.groups)
        assertEquals(3, s.favorites.size)
        assertEquals(listOf(null, null, null), s.favorites.map { it.groupId })
    }

    @Test
    fun removeDropsReading() {
        val r = BatteryReading(
            timestampMs = 0, packVoltageMv = 13_200, currentMa = 0, stateOfChargePct = 50,
            remainingCapacityMah = 50_000, nominalCapacityMah = 100_000, cycles = 0, cellVoltagesMv = emptyList(),
            temperaturesDeciC = emptyList(), chargeFetOn = true, dischargeFetOn = true, balancingMask = 0,
            protections = emptyList(), productionDate = "", firmwareVersion = "", model = "",
        )
        val s = base.copy(readings = mapOf(macs[0] to r)).removeFavorite(macs[0])
        assertEquals(2, s.favorites.size)
        assertEquals(emptyMap(), s.readings)
    }
}
