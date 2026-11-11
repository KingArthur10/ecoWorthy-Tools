package app.batterycommander.domain

import kotlinx.serialization.Serializable

/**
 * Edits to favorites and groups, shared by the phone and the hub so both enforce the same rules.
 * Each returns a new [StoredState]; invalid input throws [IllegalArgumentException].
 */

const val MAX_NAME_LENGTH = 40

private val MAC = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")

/** A battery seen by a scan. */
@Serializable
data class DiscoveredDevice(val mac: String, val name: String?, val rssiDbm: Int?)

fun normalizeMac(mac: String): String {
    val m = mac.trim().uppercase()
    require(MAC.matches(m)) { "Not a Bluetooth address: $mac" }
    return m
}

fun cleanName(name: String): String {
    val n = name.trim()
    require(n.isNotEmpty()) { "Name can't be empty" }
    require(n.length <= MAX_NAME_LENGTH) { "Name is longer than $MAX_NAME_LENGTH characters" }
    return n
}

/** Adds a favorite at the end, or renames it if it's already a favorite. */
fun StoredState.addFavorite(mac: String, nickname: String, bleName: String? = null): StoredState {
    val m = normalizeMac(mac)
    val n = cleanName(nickname)
    return if (favorites.any { it.mac == m }) renameFavorite(m, n)
    else copy(favorites = favorites + Favorite(mac = m, nickname = n, bleName = bleName))
}

fun StoredState.renameFavorite(mac: String, nickname: String): StoredState {
    val n = cleanName(nickname)
    return copy(favorites = favorites.map { if (it.mac == mac) it.copy(nickname = n) else it })
}

/** Removes a favorite and its cached reading. */
fun StoredState.removeFavorite(mac: String): StoredState =
    copy(favorites = favorites.filterNot { it.mac == mac }, readings = readings - mac, recent = recent - mac)

/** Estimate thresholds; validate them with [socThresholds]. */
fun StoredState.setFavoriteThresholds(mac: String, t: SocThresholds): StoredState =
    copy(favorites = favorites.map { if (it.mac == mac) it.copy(thresholds = t) else it })

fun StoredState.setGroupThresholds(id: String, t: SocThresholds): StoredState =
    copy(groups = groups.map { if (it.id == id) it.copy(thresholds = t) else it })

fun StoredState.setTotalThresholds(t: SocThresholds): StoredState = copy(totalThresholds = t)

/** Moves a favorite up (negative) or down (positive) in display order, clamped to the list. */
fun StoredState.moveFavorite(mac: String, delta: Int): StoredState {
    val list = favorites.toMutableList()
    val from = list.indexOfFirst { it.mac == mac }
    if (from < 0) return this
    val to = (from + delta).coerceIn(0, list.lastIndex)
    if (from == to) return this
    list.add(to, list.removeAt(from))
    return copy(favorites = list)
}

/** Puts a favorite in a group, or takes it out with null. A battery is in at most one group. */
fun StoredState.assignGroup(mac: String, groupId: String?): StoredState {
    require(groupId == null || groups.any { it.id == groupId }) { "No such group: $groupId" }
    return copy(favorites = favorites.map { if (it.mac == mac) it.copy(groupId = groupId) else it })
}

fun StoredState.createGroup(id: String, name: String, wiring: Wiring): StoredState {
    require(groups.none { it.id == id }) { "Group id already exists: $id" }
    return copy(groups = groups + BatteryGroup(id, cleanName(name), wiring))
}

fun StoredState.updateGroup(id: String, name: String? = null, wiring: Wiring? = null): StoredState {
    val clean = name?.let(::cleanName)
    return copy(
        groups = groups.map {
            if (it.id == id) it.copy(name = clean ?: it.name, wiring = wiring ?: it.wiring) else it
        },
    )
}

/** Deletes a group; its batteries stay favorites, ungrouped. */
fun StoredState.deleteGroup(id: String): StoredState = copy(
    groups = groups.filterNot { it.id == id },
    favorites = favorites.map { if (it.groupId == id) it.copy(groupId = null) else it },
)
