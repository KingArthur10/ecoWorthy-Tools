package app.batterycommander.hub.web

import app.batterycommander.domain.AggregateStats
import app.batterycommander.domain.BatteryGroup
import app.batterycommander.domain.BatteryReading
import app.batterycommander.domain.Favorite
import app.batterycommander.domain.summarize
import app.batterycommander.hub.HubService
import app.batterycommander.hub.PollStatus
import app.batterycommander.hub.store.HistoryDb
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BatteryDto(val favorite: Favorite, val reading: BatteryReading?, val status: PollStatus)

@Serializable
data class GroupDto(val group: BatteryGroup, val stats: AggregateStats?, val members: List<String>)

/** Everything the dashboard renders. Batteries are referenced by MAC from groups/ungrouped. */
@Serializable
data class OverviewDto(
    val nowMs: Long,
    val pollIntervalMs: Long,
    val total: AggregateStats?,
    val groups: List<GroupDto>,
    val ungrouped: List<String>,
    val batteries: Map<String, BatteryDto>,
)

fun HubService.overview(nowMs: Long = System.currentTimeMillis()): OverviewDto {
    val s = state.value
    val summary = s.summarize()
    val statuses = statuses.value
    return OverviewDto(
        nowMs = nowMs,
        pollIntervalMs = pollInterval.inWholeMilliseconds,
        total = summary.total,
        groups = summary.groups.map { g -> GroupDto(g.group, g.stats, g.members.map { it.mac }) },
        ungrouped = summary.ungrouped.map { it.mac },
        batteries = s.favorites.associate { f -> f.mac to BatteryDto(f, s.readings[f.mac], statuses[f.mac] ?: PollStatus()) },
    )
}

/**
 * JSON API plus the static dashboard (resources/web). Read-only plus "refresh": there is no
 * authentication yet, so nothing here may change battery state (docs/safety.md).
 */
fun Application.hubModule(service: HubService, history: HistoryDb, jobs: CoroutineScope) {
    install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
    routing {
        get("/api/overview") { call.respond(service.overview()) }

        get("/api/batteries/{mac}/history") {
            val mac = call.parameters["mac"]!!
            val hours = call.request.queryParameters["hours"]?.toLongOrNull()?.coerceIn(1, 24 * 90) ?: 24
            if (service.state.value.favorites.none { it.mac == mac }) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Not a favorite: $mac"))
                return@get
            }
            call.respond(history.history(mac, System.currentTimeMillis() - hours * 3_600_000))
        }

        post("/api/batteries/{mac}/refresh") {
            val mac = call.parameters["mac"]!!
            if (service.state.value.favorites.none { it.mac == mac }) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Not a favorite: $mac"))
                return@post
            }
            jobs.launch { service.refresh(mac) }
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "refreshing"))
        }

        post("/api/refresh") {
            jobs.launch { service.refreshAll() }
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "refreshing"))
        }

        staticResources("/", "web")
    }
}
