package app.batterycommander.domain

import kotlinx.serialization.Serializable

/**
 * Everything a node (phone or hub) persists about the fleet: favorites in display order, each
 * one's last reading, and groups. The same JSON format on both, so favorites can be copied between them.
 */
@Serializable
data class StoredState(
    val version: Int = 1,
    val favorites: List<Favorite> = emptyList(),
    val readings: Map<String, BatteryReading> = emptyMap(),
    val groups: List<BatteryGroup> = emptyList(),
    /** Per-node settings: not copied between phone and hub. */
    val settings: Settings = Settings(),
)

/** Node settings. Defaults apply to files written before a setting existed. */
@Serializable
data class Settings(
    /** Minutes between automatic reads of each battery; [MANUAL_REFRESH] = only when the user asks. */
    val pollIntervalMinutes: Int = DEFAULT_POLL_MINUTES,
) {
    val isManual: Boolean get() = pollIntervalMinutes == MANUAL_REFRESH
}

const val MANUAL_REFRESH = 0
const val DEFAULT_POLL_MINUTES = 10

/** Choices offered in the UIs. Any value 1..1440 is accepted from the API. */
val POLL_INTERVAL_CHOICES = listOf(MANUAL_REFRESH, 1, 2, 5, 10, 15, 30, 60, 120)

fun pollIntervalLabel(minutes: Int): String = when {
    minutes == MANUAL_REFRESH -> "Manual only"
    minutes < 60 -> "Every $minutes min"
    minutes == 60 -> "Every hour"
    minutes % 60 == 0 -> "Every ${minutes / 60} hours"
    else -> "Every $minutes min"
}

fun StoredState.withPollInterval(minutes: Int): StoredState {
    require(minutes == MANUAL_REFRESH || minutes in 1..1440) { "Interval must be 0 (manual) or 1–1440 minutes" }
    return copy(settings = settings.copy(pollIntervalMinutes = minutes))
}

data class GroupSummary(val group: BatteryGroup, val members: List<Favorite>, val stats: AggregateStats?)

/** What an overview shows: the all-batteries total, each group with its totals, then ungrouped batteries. */
data class FleetSummary(
    /** Totals across every favorite; null until at least one has a reading. */
    val total: AggregateStats?,
    val groups: List<GroupSummary>,
    val ungrouped: List<Favorite>,
)

fun StoredState.summarize(): FleetSummary {
    val known = groups.map { it.id }.toSet()
    return FleetSummary(
        // "All batteries" aren't one bank, so only energy and Ah totals are meaningful.
        total = aggregate(favorites.map { readings[it.mac] }, Wiring.NONE),
        groups = groups.map { g ->
            val members = favorites.filter { it.groupId == g.id }
            GroupSummary(g, members, aggregate(members.map { readings[it.mac] }, g.wiring))
        },
        ungrouped = favorites.filter { it.groupId == null || it.groupId !in known },
    )
}
