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
)

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
