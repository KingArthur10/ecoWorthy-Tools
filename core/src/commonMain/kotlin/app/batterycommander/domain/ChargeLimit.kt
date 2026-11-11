package app.batterycommander.domain

/**
 * App-enforced charge limit (ADR 0002, docs/product/features/charge-limits-and-profiles.md). For a battery with
 * [Favorite.chargeLimit] on, the controlling node turns the charge MOSFET off once SOC reaches the battery's charge-to
 * threshold, and back on at [RESUME_GAP_PCT] below it. Only the charge switch is ever touched.
 */

/** Charging resumes this far below the limit, so the switch doesn't flap. */
const val RESUME_GAP_PCT = 5

/** How often to read a battery that's charging within [NEAR_LIMIT_PCT] of its limit (overshoot ≈ 0.3% at 20 A / 100 Ah). */
const val NEAR_LIMIT_POLL_MS = 60_000L
const val NEAR_LIMIT_PCT = 5

const val SOFTWARE_LOCK = "MOSFET_SOFTWARE_LOCK"

enum class ChargeAction { TURN_OFF, TURN_ON }

/**
 * What the limit calls for, given a fresh reading; null = leave it alone.
 * - Off when SOC ≥ [limitPct] and the charge switch is on (whether or not current is flowing).
 * - On when the switch was turned off by software (the BMS's lock flag) and SOC ≤ limit − [RESUME_GAP_PCT]; or right away
 *   when [rearmed] (the user just changed the limit, or turned it off) and SOC is below the limit, or the limit is off.
 * - Nothing while any real BMS protection is active: the BMS is in charge then, and a write wouldn't stick.
 * A charge switch that's off without the lock flag was turned off by the BMS itself; it's never overridden.
 */
fun chargeLimitAction(r: BatteryReading, limitPct: Int?, rearmed: Boolean): ChargeAction? {
    if (r.protections.any { it != SOFTWARE_LOCK }) return null
    val locked = !r.chargeFetOn && SOFTWARE_LOCK in r.protections
    return when {
        limitPct == null -> if (rearmed && locked) ChargeAction.TURN_ON else null
        r.chargeFetOn && limitPct < 100 && r.stateOfChargePct >= limitPct -> ChargeAction.TURN_OFF
        locked && (r.stateOfChargePct <= limitPct - RESUME_GAP_PCT || limitPct == 100 || (rearmed && r.stateOfChargePct < limitPct)) ->
            ChargeAction.TURN_ON
        else -> null
    }
}

/** The battery's limit if enforced, else null. */
val Favorite.enforcedLimitPct: Int? get() = if (chargeLimit) thresholds.high else null

/** Polls a limited battery faster while it's charging close to its limit; otherwise [baseMs]. */
fun pollIntervalMs(baseMs: Long, r: BatteryReading?, limitPct: Int?): Long =
    if (limitPct != null && r != null && r.flow == PowerFlow.CHARGING && r.stateOfChargePct >= limitPct - NEAR_LIMIT_PCT) {
        minOf(baseMs, NEAR_LIMIT_POLL_MS)
    } else {
        baseMs
    }

/** Turns the limit on or off. Refused for a series string, whose members must switch together (docs/safety.md rule 3). */
fun StoredState.setChargeLimit(mac: String, enabled: Boolean): StoredState {
    val fav = favorites.firstOrNull { it.mac == mac } ?: throw NoSuchElementException("Not a favorite: $mac")
    if (enabled) {
        val group = groups.firstOrNull { it.id == fav.groupId }
        require(group?.wiring != Wiring.SERIES) {
            "${fav.nickname} is in a series group (${group?.name}); series strings must switch together, which isn't supported yet"
        }
    }
    return copy(favorites = favorites.map { if (it.mac == mac) it.copy(chargeLimit = enabled) else it })
}
