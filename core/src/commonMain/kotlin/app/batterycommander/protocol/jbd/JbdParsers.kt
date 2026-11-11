package app.batterycommander.protocol.jbd

/**
 * Decoded 0x03 basic info. Integer units avoid floating-point rounding:
 * millivolts, milliamps, milliamp-hours, tenths of a degree Celsius.
 * Current is positive while charging.
 */
data class JbdBasicInfo(
    val packVoltageMv: Int,
    val currentMa: Int,
    val remainingCapacityMah: Int,
    val nominalCapacityMah: Int,
    val cycles: Int,
    val productionDate: JbdDate,
    /** Bit n set = cell n+1 is balancing. */
    val balancingMask: Long,
    val protections: Set<JbdProtection>,
    /** Raw protection bits, including any bits not mapped to [JbdProtection]. */
    val protectionBits: Int,
    val softwareVersion: String,
    val stateOfChargePct: Int,
    val chargeFetOn: Boolean,
    val dischargeFetOn: Boolean,
    val cellCount: Int,
    val temperaturesDeciC: List<Int>,
    /**
     * Full-charge capacity from the firmware 6.6 extended block. Inferred from captures, not from
     * documentation; null when the block is absent.
     */
    val fullChargeCapacityMah: Int?,
) {
    val powerMw: Long get() = packVoltageMv.toLong() * currentMa / 1000

    fun isBalancing(cellIndex: Int): Boolean = balancingMask and (1L shl cellIndex) != 0L
}

data class JbdDate(val year: Int, val month: Int, val day: Int) {
    override fun toString(): String = "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}

/** Protection status bits, per the JBD protocol document. */
enum class JbdProtection(val bit: Int) {
    CELL_OVERVOLTAGE(0),
    CELL_UNDERVOLTAGE(1),
    PACK_OVERVOLTAGE(2),
    PACK_UNDERVOLTAGE(3),
    CHARGE_OVERTEMPERATURE(4),
    CHARGE_UNDERTEMPERATURE(5),
    DISCHARGE_OVERTEMPERATURE(6),
    DISCHARGE_UNDERTEMPERATURE(7),
    CHARGE_OVERCURRENT(8),
    DISCHARGE_OVERCURRENT(9),
    SHORT_CIRCUIT(10),
    FRONT_END_IC_ERROR(11),
    MOSFET_SOFTWARE_LOCK(12),
    ;

    companion object {
        fun fromBits(bits: Int): Set<JbdProtection> = entries.filterTo(mutableSetOf()) { bits and (1 shl it.bit) != 0 }
    }
}

object JbdParsers {
    private const val KELVIN_OFFSET_DECI = 2731
    private const val BASIC_FIXED_SIZE = 23
    private const val EXTENDED_BLOCK_SIZE = 9

    fun basicInfo(payload: ByteArray): JbdBasicInfo {
        require(payload, BASIC_FIXED_SIZE, "basic info")
        val ntcCount = payload.u8(22)
        val tempsEnd = BASIC_FIXED_SIZE + 2 * ntcCount
        require(payload, tempsEnd, "basic info with $ntcCount temperature sensors")

        val date = payload.u16(10)
        val protectionBits = payload.u16(16)
        val fet = payload.u8(20)
        val version = payload.u8(18)

        // Firmware 6.6 extended block: 00 00 00 <full cap u16> <remaining cap u16> 00 00
        val fullCharge = if (payload.size >= tempsEnd + EXTENDED_BLOCK_SIZE) payload.u16(tempsEnd + 3) * 10 else null

        return JbdBasicInfo(
            packVoltageMv = payload.u16(0) * 10,
            currentMa = payload.s16(2) * 10,
            remainingCapacityMah = payload.u16(4) * 10,
            nominalCapacityMah = payload.u16(6) * 10,
            cycles = payload.u16(8),
            productionDate = JbdDate(year = 2000 + (date shr 9), month = (date shr 5) and 0x0F, day = date and 0x1F),
            balancingMask = payload.u16(12).toLong() or (payload.u16(14).toLong() shl 16),
            protections = JbdProtection.fromBits(protectionBits),
            protectionBits = protectionBits,
            softwareVersion = "${version shr 4}.${version and 0x0F}",
            stateOfChargePct = payload.u8(19),
            chargeFetOn = fet and 0x01 != 0,
            dischargeFetOn = fet and 0x02 != 0,
            cellCount = payload.u8(21),
            temperaturesDeciC = List(ntcCount) { payload.u16(BASIC_FIXED_SIZE + 2 * it) - KELVIN_OFFSET_DECI },
            fullChargeCapacityMah = fullCharge,
        )
    }

    /** Cell voltages in millivolts. */
    fun cellVoltages(payload: ByteArray): List<Int> {
        if (payload.size % 2 != 0) throw JbdProtocolException("Cell voltage payload has odd length ${payload.size}")
        return List(payload.size / 2) { payload.u16(2 * it) }
    }

    fun hardwareVersion(payload: ByteArray): String =
        payload.decodeToString().trimEnd('\u0000', ' ')

    private fun require(payload: ByteArray, size: Int, what: String) {
        if (payload.size < size) throw JbdProtocolException("Payload too short for $what: ${payload.size} < $size bytes")
    }
}
