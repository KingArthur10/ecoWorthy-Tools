package app.batterycommander.domain

import kotlinx.serialization.Serializable

/**
 * A user-defined group of batteries (a "bank" in docs/product/features/bank-topology-and-estimates.md).
 * A battery belongs to at most one group, matching physical wiring. Nested topologies (e.g. 2S2P) come later.
 */
@Serializable
data class BatteryGroup(
    val id: String,
    val name: String,
    val wiring: Wiring = Wiring.PARALLEL,
)

@Serializable
enum class Wiring {
    PARALLEL,
    SERIES,

    /** Grouped for organization only, not electrically joined. */
    NONE,
}

/** LiFePO4 nominal cell voltage, used to turn Ah into Wh. */
const val LFP_NOMINAL_CELL_MV = 3200

/**
 * Combined figures for a set of batteries. Energy (Wh) is topology-independent and always sums;
 * Ah, voltage, and SOC combine according to [wiring]:
 *
 * | Wiring   | Voltage | Capacity / remaining Ah | SOC                     | Current |
 * |----------|---------|-------------------------|-------------------------|---------|
 * | PARALLEL | average | sum                     | remaining ÷ capacity    | sum     |
 * | SERIES   | sum     | minimum of members      | minimum of members      | average |
 * | NONE     | n/a     | sum                     | remaining ÷ capacity    | n/a     |
 *
 * Only members with a reading count; [reportingCount] < [batteryCount] means the totals are partial.
 */
@Serializable
data class AggregateStats(
    val wiring: Wiring,
    val batteryCount: Int,
    val reportingCount: Int,
    val capacityMah: Int,
    val remainingMah: Int,
    val stateOfChargePct: Int,
    val energyCapacityMwh: Long,
    val energyRemainingMwh: Long,
    /** Combined pack voltage; null when the batteries aren't wired together. */
    val voltageMv: Int?,
    /** Current through the bank; null when the batteries aren't wired together. */
    val currentMa: Int?,
    /** Total power in (+) or out (−), valid for every wiring. */
    val powerMw: Long,
    /** Timestamp of the oldest reading included, to show how stale the totals might be. */
    val oldestReadingMs: Long,
) {
    val isPartial: Boolean get() = reportingCount < batteryCount
}

/** Capacity used for totals: the BMS's learned full-charge capacity when known, else rated. */
val BatteryReading.capacityMah: Int get() = fullChargeCapacityMah ?: nominalCapacityMah

val BatteryReading.nominalVoltageMv: Int
    get() = if (cellVoltagesMv.isNotEmpty()) cellVoltagesMv.size * LFP_NOMINAL_CELL_MV else packVoltageMv

/** Returns null when no member has a reading yet. */
fun aggregate(readings: List<BatteryReading?>, wiring: Wiring): AggregateStats? {
    val present = readings.filterNotNull()
    if (present.isEmpty()) return null

    val energyCapacity = present.sumOf { it.capacityMah.toLong() * it.nominalVoltageMv / 1000 }
    val energyRemaining = present.sumOf { it.remainingCapacityMah.toLong() * it.nominalVoltageMv / 1000 }
    val power = present.sumOf { it.powerMw }

    val capacity: Int
    val remaining: Int
    val soc: Int
    when (wiring) {
        Wiring.SERIES -> {
            capacity = present.minOf { it.capacityMah }
            remaining = present.minOf { it.remainingCapacityMah }
            soc = present.minOf { it.stateOfChargePct }
        }
        Wiring.PARALLEL, Wiring.NONE -> {
            capacity = present.sumOf { it.capacityMah }
            remaining = present.sumOf { it.remainingCapacityMah }
            soc = if (capacity == 0) 0 else ((remaining.toLong() * 100 + capacity / 2) / capacity).toInt()
        }
    }

    return AggregateStats(
        wiring = wiring,
        batteryCount = readings.size,
        reportingCount = present.size,
        capacityMah = capacity,
        remainingMah = remaining,
        stateOfChargePct = soc,
        energyCapacityMwh = energyCapacity,
        energyRemainingMwh = energyRemaining,
        voltageMv = when (wiring) {
            Wiring.PARALLEL -> present.sumOf { it.packVoltageMv } / present.size
            Wiring.SERIES -> present.sumOf { it.packVoltageMv }
            Wiring.NONE -> null
        },
        currentMa = when (wiring) {
            Wiring.PARALLEL -> present.sumOf { it.currentMa }
            Wiring.SERIES -> present.sumOf { it.currentMa } / present.size
            Wiring.NONE -> null
        },
        powerMw = power,
        oldestReadingMs = present.minOf { it.timestampMs },
    )
}
