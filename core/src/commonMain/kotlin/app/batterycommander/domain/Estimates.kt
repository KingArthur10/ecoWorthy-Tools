package app.batterycommander.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

/**
 * Time-to-threshold estimates (FR-022, FR-053, FR-055). See docs/product/features/bank-topology-and-estimates.md
 * and ADR 0004.
 *
 * The rate is the change in the BMS's remaining capacity over a rolling window, not a snapshot of the current: the
 * BMS counts every coulomb between polls, so a load that cycles (a fridge compressor) averages out correctly.
 */

/**
 * Charge levels the estimates count toward, for a battery, a group, or all batteries. Used for estimates only:
 * nothing is sent to the BMS. Null means the default (0% and 100%).
 */
@Serializable
data class SocThresholds(
    val dischargeToPct: Int? = null,
    val chargeToPct: Int? = null,
) {
    val low: Int get() = dischargeToPct ?: 0
    val high: Int get() = chargeToPct ?: 100
}

/** Validates user input. 0 and 100 are stored as the default (null). */
fun socThresholds(dischargeToPct: Int?, chargeToPct: Int?): SocThresholds {
    require(dischargeToPct == null || dischargeToPct in 0..100) { "Discharge threshold must be 0–100%" }
    require(chargeToPct == null || chargeToPct in 0..100) { "Charge threshold must be 0–100%" }
    require((dischargeToPct ?: 0) < (chargeToPct ?: 100)) { "The discharge threshold must be below the charge threshold" }
    return SocThresholds(dischargeToPct.takeIf { it != 0 }, chargeToPct.takeIf { it != 100 })
}

/** The part of a reading the rolling rate needs. Kept per battery in [StoredState.recent]. */
@Serializable
data class RateSample(val t: Long, val remainingMah: Int, val currentMa: Int)

fun BatteryReading.toSample() = RateSample(timestampMs, remainingCapacityMah, currentMa)

/** The rolling window the rate averages over. */
const val ESTIMATE_WINDOW_MS = 30 * 60_000L

/** With slow polling no earlier sample may fall inside the window; reach back this far for one. Also the retention. */
const val MAX_RATE_SPAN_MS = 3 * 3_600_000L

/** Shorter spans are dominated by the BMS's 10 mAh resolution; use the instantaneous current instead. */
const val MIN_RATE_SPAN_MS = 2 * 60_000L

/** A rate over less history than this is shown as rough. */
const val CONFIDENT_SPAN_MS = 10 * 60_000L

/** The latest current may differ from the average by this much (or by the average itself) and still count as steady. */
const val STEADY_TOLERANCE_MA = 2_000

const val MAX_RECENT_SAMPLES = 200

/** Average current (positive = charging) and how much history it covers; [spanMs] 0 = the latest reading alone. */
data class Rate(val currentMa: Int, val spanMs: Long, val confident: Boolean)

/**
 * The battery's average current over the last [ESTIMATE_WINDOW_MS], from the change in remaining capacity between
 * the oldest sample in the window and [latest]. Falls back to the latest current (as rough) when there's no usable
 * earlier sample, or when the flow just changed direction (e.g. the sun came up), since the window would mix both,
 * or stopped because its MOSFET was switched off.
 */
fun rollingRate(latest: BatteryReading, samples: List<RateSample>): Rate {
    val now = latest.timestampMs
    val instant = Rate(latest.currentMa, 0, confident = false)
    val earlier = samples.filter { it.t <= now - MIN_RATE_SPAN_MS && it.t >= now - MAX_RATE_SPAN_MS }
    val base = earlier.filter { it.t >= now - ESTIMATE_WINDOW_MS }.minByOrNull { it.t }
        ?: earlier.maxByOrNull { it.t }
        ?: return instant
    val span = now - base.t
    val avg = ((latest.remainingCapacityMah - base.remainingMah).toLong() * 3_600_000 / span).toInt()
    val latestFlow = flowOf(latest.currentMa)
    if (latestFlow != PowerFlow.IDLE && latestFlow != flowOf(avg)) return instant
    // Idle because that direction's switch is now off (e.g. held at a charge limit): the window's flow has stopped.
    if (latestFlow == PowerFlow.IDLE && ((avg > 0 && !latest.chargeFetOn) || (avg < 0 && !latest.dischargeFetOn))) return instant
    val steady = abs(latest.currentMa - avg) <= max(STEADY_TOLERANCE_MA, abs(avg))
    return Rate(avg, span, confident = span >= CONFIDENT_SPAN_MS && steady)
}

/** How long until a battery or group reaches its threshold, counted from [asOfMs] (its reading time). */
@Serializable
data class TimeEstimate(
    val flow: PowerFlow,
    /** The charge threshold while charging, the discharge threshold while discharging; null when idle. */
    val targetPct: Int?,
    /** From [asOfMs] to [targetPct]; 0 = already there; null when idle. */
    val durationMs: Long?,
    /** The average current used, positive = charging. */
    val rateMa: Int,
    /** History the rate covers; 0 = a single reading. For a group, the shortest of its members. */
    val spanMs: Long,
    /** Reading time the estimate starts from; for a group, its oldest member's. */
    val asOfMs: Long,
    /** False when based on too little history, a changing load, or (for a group) missing members. Show it greyed. */
    val confident: Boolean,
) {
    val etaMs: Long? get() = durationMs?.let { asOfMs + it }
}

/** Time to move [remainingMah] to [targetPct] of [capacityMah] at [rateMa]; 0 if already there. */
private fun msToTarget(remainingMah: Long, capacityMah: Long, targetPct: Int, rateMa: Int): Long {
    val targetMah = capacityMah * targetPct / 100
    val gap = if (rateMa > 0) targetMah - remainingMah else remainingMah - targetMah
    return if (gap <= 0) 0 else gap * 3_600_000 / abs(rateMa)
}

private fun SocThresholds.targetFor(flow: PowerFlow): Int? = when (flow) {
    PowerFlow.CHARGING -> high
    PowerFlow.DISCHARGING -> low
    PowerFlow.IDLE -> null
}

fun estimate(r: BatteryReading, rate: Rate, thresholds: SocThresholds): TimeEstimate {
    val flow = flowOf(rate.currentMa)
    val target = thresholds.targetFor(flow)
    return TimeEstimate(
        flow = flow,
        targetPct = target,
        durationMs = target?.let { msToTarget(r.remainingCapacityMah.toLong(), r.capacityMah.toLong(), it, rate.currentMa) },
        rateMa = rate.currentMa,
        spanMs = rate.spanMs,
        asOfMs = r.timestampMs,
        confident = rate.confident,
    )
}

/**
 * A group's estimate, combined the same way as [aggregate]: parallel and unwired groups pool their amp-hours and
 * add their currents. A series string carries one current, so each member is projected at the average current
 * against the group's thresholds: a discharge ends when the first member reaches the threshold, and a charge (to
 * the string's SOC, which is its lowest member's) when the last one does. [members] are those with a reading;
 * fewer than [batteryCount] makes it rough.
 */
fun estimateGroup(
    members: List<Pair<BatteryReading, Rate>>,
    wiring: Wiring,
    thresholds: SocThresholds,
    batteryCount: Int,
): TimeEstimate? {
    if (members.isEmpty()) return null
    val rate = when (wiring) {
        Wiring.SERIES -> members.sumOf { it.second.currentMa } / members.size
        Wiring.PARALLEL, Wiring.NONE -> members.sumOf { it.second.currentMa }
    }
    val flow = flowOf(rate)
    val target = thresholds.targetFor(flow)
    val duration = target?.let {
        when (wiring) {
            Wiring.SERIES -> {
                val each = members.map { (r, _) -> msToTarget(r.remainingCapacityMah.toLong(), r.capacityMah.toLong(), it, rate) }
                if (flow == PowerFlow.DISCHARGING) each.min() else each.max()
            }
            Wiring.PARALLEL, Wiring.NONE -> msToTarget(
                members.sumOf { (r, _) -> r.remainingCapacityMah.toLong() },
                members.sumOf { (r, _) -> r.capacityMah.toLong() },
                it, rate,
            )
        }
    }
    return TimeEstimate(
        flow = flow,
        targetPct = target,
        durationMs = duration,
        rateMa = rate,
        spanMs = members.minOf { it.second.spanMs },
        asOfMs = members.minOf { it.first.timestampMs },
        confident = members.size == batteryCount && members.all { it.second.confident },
    )
}

/** "under a minute", "45 min", "3 h 10 min", "2 d 4 h". */
fun formatDuration(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 1 -> "under a minute"
        min < 60 -> "$min min"
        min < 24 * 60 -> if (min % 60 == 0L) "${min / 60} h" else "${min / 60} h ${min % 60} min"
        min < 100L * 24 * 60 -> if (min / 60 % 24 == 0L) "${min / 1440} d" else "${min / 1440} d ${min / 60 % 24} h"
        else -> "over 100 days"
    }
}

/**
 * "Full in 3 h 10 min", "Down to 20% in 6 h", "At or above 90%", or null when idle. Counts down from [nowMs], so an
 * older reading shows less time left, and one projected past its threshold says so.
 */
fun TimeEstimate.describe(nowMs: Long): String? {
    val target = targetPct ?: return null
    val duration = durationMs ?: return null
    val charging = flow == PowerFlow.CHARGING
    val name = when {
        charging && target == 100 -> "Full"
        !charging && target == 0 -> "Empty"
        charging -> "Up to $target%"
        else -> "Down to $target%"
    }
    val left = asOfMs + duration - nowMs
    return when {
        duration == 0L -> when {
            charging && target == 100 -> "Full"
            !charging && target == 0 -> "Empty"
            charging -> "At or above $target%"
            else -> "At or below $target%"
        }
        left <= 0 -> "$name by now, if the rate held"
        else -> "$name in ${formatDuration(left)}"
    }
}

/** "Average over the last 28 min" / "Based on the latest reading only". */
fun TimeEstimate.basis(): String =
    if (spanMs == 0L) "Based on the latest reading only" else "Average over the last ${formatDuration(spanMs)}"
