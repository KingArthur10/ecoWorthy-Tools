package app.batterycommander.hub.web

import app.batterycommander.domain.AggregateStats
import app.batterycommander.domain.BatteryGroup
import app.batterycommander.domain.BatteryReading
import app.batterycommander.domain.Favorite
import app.batterycommander.domain.Settings
import app.batterycommander.domain.SocThresholds
import app.batterycommander.domain.TimeEstimate
import app.batterycommander.domain.socThresholds
import app.batterycommander.domain.Wiring
import app.batterycommander.domain.normalizeMac
import app.batterycommander.domain.summarize
import app.batterycommander.hub.HubService
import app.batterycommander.hub.PollStatus
import app.batterycommander.hub.store.HistoryDb
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

@Serializable
data class BatteryDto(val favorite: Favorite, val reading: BatteryReading?, val status: PollStatus, val estimate: TimeEstimate? = null)

@Serializable
data class GroupDto(val group: BatteryGroup, val stats: AggregateStats?, val members: List<String>, val estimate: TimeEstimate? = null)

/** Everything the dashboard renders. Batteries are referenced by MAC from groups/ungrouped. */
@Serializable
data class OverviewDto(
    val nowMs: Long,
    val settings: Settings,
    val total: AggregateStats?,
    val totalEstimate: TimeEstimate? = null,
    val totalThresholds: SocThresholds = SocThresholds(),
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
        settings = s.settings,
        total = summary.total,
        totalEstimate = summary.totalEstimate,
        totalThresholds = s.totalThresholds,
        groups = summary.groups.map { g -> GroupDto(g.group, g.stats, g.members.map { it.mac }, g.estimate) },
        ungrouped = summary.ungrouped.map { it.mac },
        batteries = s.favorites.associate { f ->
            f.mac to BatteryDto(f, s.readings[f.mac], statuses[f.mac] ?: PollStatus(), summary.estimates[f.mac])
        },
    )
}

@Serializable
data class AddFavoriteRequest(val mac: String, val nickname: String)

@Serializable
data class NicknameRequest(val nickname: String)

@Serializable
data class MoveRequest(val delta: Int)

@Serializable
data class GroupAssignRequest(val groupId: String? = null)

@Serializable
data class GroupRequest(val name: String? = null, val wiring: Wiring? = null)

@Serializable
data class ScanRequest(val seconds: Int = 15)

@Serializable
data class SettingsRequest(val pollIntervalMinutes: Int)

/** Estimate thresholds in percent; null (or omitted) = the default, 0% and 100%. */
@Serializable
data class ThresholdsRequest(val dischargeToPct: Int? = null, val chargeToPct: Int? = null) {
    fun validated(): SocThresholds = socThresholds(dischargeToPct, chargeToPct)
}

@Serializable
data class ErrorDto(val error: String)

/** Header every state-changing request must carry. Other websites can't add it to a request to us. */
const val CSRF_HEADER = "X-Battery-Hub"

/** Host names the hub answers to when bound to loopback; anything else is a DNS-rebinding attempt. */
val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")

/**
 * JSON API plus the static dashboard (resources/web). It changes configuration (favorites,
 * groups, estimate thresholds) but never battery state: there's no authentication yet, so control endpoints wait
 * for pairing (docs/safety.md).
 *
 * Guards: requests must name an allowed Host (stops DNS rebinding), and anything other than
 * GET/HEAD must carry [CSRF_HEADER] (stops cross-site requests from pages in the user's browser).
 */
fun Application.hubModule(
    service: HubService,
    history: HistoryDb,
    jobs: CoroutineScope,
    allowedHosts: Set<String> = LOOPBACK_HOSTS,
) {
    install(ContentNegotiation) { json(Json { encodeDefaults = true; ignoreUnknownKeys = true }) }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorDto(e.message ?: "Bad request")) }
        exception<BadRequestException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorDto(e.message ?: "Bad request")) }
        exception<NoSuchElementException> { call, e -> call.respond(HttpStatusCode.NotFound, ErrorDto(e.message ?: "Not found")) }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        val host = call.request.headers[HttpHeaders.Host].orEmpty().let { h ->
            if (h.startsWith("[")) h.substringAfter("[").substringBefore("]") else h.substringBefore(":")
        }
        val method = call.request.httpMethod
        when {
            host !in allowedHosts -> call.respond(HttpStatusCode.Forbidden, ErrorDto("Unexpected Host header"))
            method != HttpMethod.Get && method != HttpMethod.Head && call.request.headers[CSRF_HEADER] != "1" ->
                call.respond(HttpStatusCode.Forbidden, ErrorDto("Missing $CSRF_HEADER header"))
            else -> return@intercept
        }
        finish()
    }

    routing {
        get("/api/overview") { call.respond(service.overview()) }

        get("/api/batteries/{mac}/history") {
            val mac = call.parameters["mac"]!!
            val hours = call.request.queryParameters["hours"]?.toLongOrNull()?.coerceIn(1, 24 * 90) ?: 24
            if (!service.isFavorite(mac)) throw NoSuchElementException("Not a favorite: $mac")
            call.respond(history.history(mac, System.currentTimeMillis() - hours * 3_600_000))
        }

        post("/api/batteries/{mac}/refresh") {
            val mac = call.parameters["mac"]!!
            if (!service.isFavorite(mac)) throw NoSuchElementException("Not a favorite: $mac")
            jobs.launch { service.refresh(mac) }
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "refreshing"))
        }

        post("/api/refresh") {
            jobs.launch { service.refreshAll() }
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "refreshing"))
        }

        // --- Settings ---
        get("/api/settings") { call.respond(service.state.value.settings) }
        put("/api/settings") {
            service.setPollInterval(call.receive<SettingsRequest>().pollIntervalMinutes)
            call.respond(service.state.value.settings)
        }

        // --- Discovery ---
        get("/api/scan") { call.respond(service.scanResults()) }

        post("/api/scan") {
            val seconds = call.receive<ScanRequest>().seconds.coerceIn(5, 60)
            if (service.scan.value.scanning) {
                call.respond(HttpStatusCode.Conflict, ErrorDto("A scan is already running"))
                return@post
            }
            jobs.launch { service.runScan(seconds.seconds) }
            call.respond(HttpStatusCode.Accepted, service.scanResults().copy(scanning = true))
        }

        post("/api/identify/{mac}") {
            service.identify(call.parameters["mac"]!!).fold(
                onSuccess = { call.respond(it) },
                onFailure = { call.respond(HttpStatusCode.BadGateway, ErrorDto(it.message ?: it.toString())) },
            )
        }

        // --- Favorites ---
        post("/api/favorites") {
            val req = call.receive<AddFavoriteRequest>()
            service.addFavorite(req.mac, req.nickname)
            val mac = normalizeMac(req.mac)
            jobs.launch { service.refresh(mac) } // read it right away
            call.respond(HttpStatusCode.Created, mapOf("mac" to mac))
        }
        put("/api/favorites/{mac}/nickname") {
            service.renameFavorite(call.parameters["mac"]!!, call.receive<NicknameRequest>().nickname)
            call.respond(HttpStatusCode.NoContent)
        }
        put("/api/favorites/{mac}/group") {
            service.assignGroup(call.parameters["mac"]!!, call.receive<GroupAssignRequest>().groupId)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/api/favorites/{mac}/move") {
            service.moveFavorite(call.parameters["mac"]!!, call.receive<MoveRequest>().delta)
            call.respond(HttpStatusCode.NoContent)
        }
        put("/api/favorites/{mac}/thresholds") {
            service.setFavoriteThresholds(call.parameters["mac"]!!, call.receive<ThresholdsRequest>().validated())
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/api/favorites/{mac}") {
            service.removeFavorite(call.parameters["mac"]!!)
            call.respond(HttpStatusCode.NoContent)
        }

        // --- Groups ---
        post("/api/groups") {
            val req = call.receive<GroupRequest>()
            val id = service.createGroup(req.name ?: throw IllegalArgumentException("Group needs a name"), req.wiring ?: Wiring.PARALLEL)
            call.respond(HttpStatusCode.Created, mapOf("id" to id))
        }
        patch("/api/groups/{id}") {
            val req = call.receive<GroupRequest>()
            service.updateGroup(call.parameters["id"]!!, req.name, req.wiring)
            call.respond(HttpStatusCode.NoContent)
        }
        put("/api/groups/{id}/thresholds") {
            service.setGroupThresholds(call.parameters["id"]!!, call.receive<ThresholdsRequest>().validated())
            call.respond(HttpStatusCode.NoContent)
        }
        put("/api/thresholds") {
            service.setTotalThresholds(call.receive<ThresholdsRequest>().validated())
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/api/groups/{id}") {
            service.deleteGroup(call.parameters["id"]!!)
            call.respond(HttpStatusCode.NoContent)
        }

        staticResources("/", "web")
    }
}
