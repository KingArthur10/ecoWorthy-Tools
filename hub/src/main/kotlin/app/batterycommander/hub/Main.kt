package app.batterycommander.hub

import app.batterycommander.domain.Favorite
import app.batterycommander.domain.MANUAL_REFRESH
import app.batterycommander.domain.StoredState
import app.batterycommander.domain.SocThresholds
import app.batterycommander.domain.TimeEstimate
import app.batterycommander.domain.describe
import app.batterycommander.domain.pollIntervalLabel
import app.batterycommander.domain.setFavoriteThresholds
import app.batterycommander.domain.setGroupThresholds
import app.batterycommander.domain.setTotalThresholds
import app.batterycommander.domain.socThresholds
import app.batterycommander.domain.withPollInterval
import app.batterycommander.domain.formatAge
import app.batterycommander.hub.bluez.BluezBle
import app.batterycommander.hub.store.HistoryDb
import app.batterycommander.hub.store.HubPaths
import app.batterycommander.hub.store.StateStore
import app.batterycommander.hub.web.LOOPBACK_HOSTS
import app.batterycommander.hub.web.OverviewDto
import app.batterycommander.hub.web.hubModule
import app.batterycommander.hub.web.overview
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.types.restrictTo
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_PORT = 8640

class Hub : CliktCommand(name = "battery-hub") {
    override fun run() = Unit
}

class Serve : CliktCommand() {
    private val host by option(help = "Address to listen on. Default: this machine only.").default("127.0.0.1")
    private val port by option(help = "HTTP port").int().default(DEFAULT_PORT)
    private val adapter by option(help = "Bluetooth adapter").default("hci0")

    override fun run() = runBlocking {
        val store = StateStore()
        val history = HistoryDb()
        val ble = BluezBle(adapter)
        val service = HubService(store, history, BluezReader(ble), BluezScanner(ble))
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val poller = jobs.launch { service.runPoller() }
        val allowedHosts = LOOPBACK_HOSTS + host
        val server = embeddedServer(CIO, port = port, host = host) { hubModule(service, history, jobs, allowedHosts) }

        // On stop (Ctrl-C, systemctl stop), let any in-flight read finish its clean disconnect.
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runBlocking {
                    server.stop(1000, 2000)
                    withTimeoutOrNull(15.seconds) { jobs.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }
                }
                history.close()
                ble.close()
            },
        )
        val s = service.state.value
        echo("Battery hub: ${s.favorites.size} favorites, refresh: ${pollIntervalLabel(s.settings.pollIntervalMinutes).lowercase()}")
        echo("Data: ${HubPaths.dataDir}")
        echo("Dashboard: http://$host:$port/")
        server.start(wait = false)
        poller.join()
    }
}

class Status : CliktCommand() {
    private val url by option(help = "Hub URL").default("http://127.0.0.1:$DEFAULT_PORT")

    override fun run() {
        val json = Json { ignoreUnknownKeys = true }
        val o = try {
            json.decodeFromString<OverviewDto>(get("$url/api/overview"))
        } catch (e: Exception) {
            // Hub not running: show what's on disk, without live status.
            echo("(hub not reachable at $url; showing saved data)", err = true)
            val store = StateStore()
            HubService(store, HistoryDb(), { error("offline") }).overview()
        }
        val now = o.nowMs
        echo("Refresh: ${pollIntervalLabel(o.settings.pollIntervalMinutes).lowercase()}")
        o.total?.let {
            echo(
                String.format(
                    Locale.US, "ALL  %d%%  %.1f/%.1f Ah  %.2f/%.2f kWh  %+.0f W%s",
                    it.stateOfChargePct, it.remainingMah / 1000.0, it.capacityMah / 1000.0,
                    it.energyRemainingMwh / 1e6, it.energyCapacityMwh / 1e6, it.powerMw / 1000.0,
                    if (it.isPartial) "  (${it.reportingCount}/${it.batteryCount} reporting)" else "",
                ) + estimateText(o.totalEstimate, now),
            )
        }
        val sections = o.groups.map { "${it.group.name} (${it.group.wiring.name.lowercase()})" + estimateText(it.estimate, now) to it.members } +
            listOf("Not in a group" to o.ungrouped)
        for ((title, macs) in sections) {
            if (macs.isEmpty()) continue
            echo("\n$title")
            for (mac in macs) {
                val b = o.batteries[mac] ?: continue
                val r = b.reading
                val line = if (r == null) "no data" else String.format(
                    Locale.US, "%3d%%  %6.2f V  %+6.1f A  %5.1f/%5.1f Ah  %s",
                    r.stateOfChargePct, r.packVoltageMv / 1000.0, r.currentMa / 1000.0,
                    r.remainingCapacityMah / 1000.0, (r.fullChargeCapacityMah ?: r.nominalCapacityMah) / 1000.0,
                    formatAge(now, r.timestampMs),
                )
                val status = when (b.status.state) {
                    PollStatus.State.FAILED -> "  [last read failed: ${b.status.message}]"
                    PollStatus.State.REFRESHING -> "  [refreshing]"
                    PollStatus.State.IDLE -> ""
                }
                echo(String.format(Locale.US, "  %-20s %s%s%s", b.favorite.nickname.take(20), line, estimateText(b.estimate, now), status))
            }
        }
    }
}

/** "  · Full in 3 h (rough)", or "" when idle. */
private fun estimateText(e: TimeEstimate?, now: Long): String =
    e?.describe(now)?.let { "  · $it" + if (e.confident) "" else " (rough)" }.orEmpty()

class Thresholds : CliktCommand() {
    private val target by argument(help = "A battery's MAC or nickname, a group name, or \"all\"")
    private val dischargeTo by option("--discharge-to", help = "Discharge estimates count down to this % (0 = default)").int().restrictTo(0..100)
    private val chargeTo by option("--charge-to", help = "Charge estimates count up to this % (100 = default)").int().restrictTo(0..100)
    private val url by option(help = "Hub URL").default("http://127.0.0.1:$DEFAULT_PORT")

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Show or set the levels time estimates count toward. For estimates only: nothing is sent to the battery. " +
            "Options left out keep their current value."

    override fun run() {
        val store = StateStore()
        val s = store.load()
        val fav = s.favorites.firstOrNull { it.mac.equals(target, ignoreCase = true) }
            ?: s.favorites.filter { it.nickname.equals(target, ignoreCase = true) }.singleOrNull()
        val group = s.groups.filter { it.name.equals(target, ignoreCase = true) }.singleOrNull()
        val isAll = target.equals("all", ignoreCase = true)
        val (label, current, path) = when {
            isAll -> Triple("All batteries", s.totalThresholds, "/api/thresholds")
            fav != null -> Triple(fav.nickname, fav.thresholds, "/api/favorites/${fav.mac}/thresholds")
            group != null -> Triple("Group ${group.name}", group.thresholds, "/api/groups/${group.id}/thresholds")
            else -> throw CliktError("No single battery or group matches \"$target\" (use a MAC if nicknames repeat)")
        }
        if (dischargeTo == null && chargeTo == null) {
            echo("$label: ${describe(current)}")
            return
        }
        val t = try {
            socThresholds(dischargeTo ?: current.low, chargeTo ?: current.high)
        } catch (e: IllegalArgumentException) {
            throw CliktError(e.message)
        }
        try {
            put(url + path, """{"dischargeToPct":${t.dischargeToPct},"chargeToPct":${t.chargeToPct}}""")
        } catch (e: HubUnreachable) {
            // Hub not running: write it to disk; it applies on next start.
            store.save(
                when {
                    isAll -> s.setTotalThresholds(t)
                    fav != null -> s.setFavoriteThresholds(fav.mac, t)
                    else -> s.setGroupThresholds(group!!.id, t)
                },
            )
        }
        echo("$label: ${describe(t)}")
    }

    private fun describe(t: SocThresholds) = "discharge to ${t.low}%, charge to ${t.high}%"
}

class Refresh : CliktCommand() {
    private val mac by argument(help = "Battery MAC; omit for all").optional()
    private val url by option(help = "Hub URL").default("http://127.0.0.1:$DEFAULT_PORT")

    override fun run() {
        post(if (mac == null) "$url/api/refresh" else "$url/api/batteries/$mac/refresh")
        echo("Refresh requested. Check `battery-hub status` in a few seconds.")
    }
}

class Interval : CliktCommand() {
    private val value by argument(help = "Minutes between automatic reads (1–1440), or \"manual\"")
    private val url by option(help = "Hub URL").default("http://127.0.0.1:$DEFAULT_PORT")

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Set how often the hub reads each battery. \"manual\" keeps the radio idle except on refresh/scan."

    override fun run() {
        val minutes = if (value.equals("manual", ignoreCase = true)) MANUAL_REFRESH
        else value.toIntOrNull() ?: throw CliktError("Expected minutes or \"manual\", got: $value")
        try {
            StoredState().withPollInterval(minutes) // validate before touching anything
        } catch (e: IllegalArgumentException) {
            throw CliktError(e.message)
        }
        val body = """{"pollIntervalMinutes":$minutes}"""
        try {
            put("$url/api/settings", body)
        } catch (e: HubUnreachable) {
            // Hub not running: write the setting to disk; it applies on next start.
            val store = StateStore()
            store.save(store.load().withPollInterval(minutes))
        }
        echo("Refresh: ${pollIntervalLabel(minutes).lowercase()}")
    }
}

class Charge : CliktCommand() {
    private val target by argument(help = "A favorite's MAC or nickname")
    private val state by argument(help = "on or off").convert {
        when (it.lowercase()) {
            "on" -> true
            "off" -> false
            else -> fail("Expected on or off, got: $it")
        }
    }
    private val yes by option("--yes", help = "Don't ask for confirmation").flag()
    private val adapter by option(help = "Bluetooth adapter").default("hci0")
    private val url by option(help = "Hub URL (to check it isn't running)").default("http://127.0.0.1:$DEFAULT_PORT")

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Turn a battery's charge MOSFET on or off, then read it back to confirm. Stop the hub service first " +
            "(systemctl --user stop battery-hub): it holds the radio, and has no control API until pairing exists."

    override fun run(): Unit = runBlocking {
        if (runCatching { get("$url/api/settings") }.isSuccess) {
            throw CliktError("The hub is running at $url. Stop it first: systemctl --user stop battery-hub")
        }
        val store = StateStore()
        val s = store.load()
        val fav = s.favorites.firstOrNull { it.mac.equals(target, ignoreCase = true) }
            ?: s.favorites.filter { it.nickname.equals(target, ignoreCase = true) }.singleOrNull()
            ?: throw CliktError("No single favorite matches \"$target\" (use its MAC if nicknames repeat)")
        val what = if (state) "Turn charging ON for ${fav.nickname} (${fav.mac})?"
        else "Turn charging OFF for ${fav.nickname} (${fav.mac})? It stops accepting charge until turned back on; loads keep power."
        if (!yes) {
            echo("$what [y/N] ", trailingNewline = false)
            if (readlnOrNull()?.trim()?.lowercase() !in setOf("y", "yes")) throw CliktError("Cancelled")
        }

        HistoryDb().use { history ->
            BluezBle(adapter).use { ble ->
                val service = HubService(store, history, BluezReader(ble), controller = BluezController(ble))
                try {
                    service.setCharge(fav.mac, state, source = "user")
                        .onSuccess { r ->
                            echo(
                                String.format(
                                    Locale.US, "Done. %s: charge %s, discharge %s · %d%% · %.2f V · %+.1f A%s",
                                    fav.nickname, if (r.chargeFetOn) "ON" else "OFF", if (r.dischargeFetOn) "ON" else "OFF",
                                    r.stateOfChargePct, r.packVoltageMv / 1000.0, r.currentMa / 1000.0,
                                    if (r.protections.isEmpty()) "" else " · " + r.protections.joinToString(),
                                ),
                            )
                        }
                        .onFailure { throw CliktError("Failed: ${it.message}") }
                } catch (e: IllegalArgumentException) {
                    throw CliktError(e.message)
                } finally {
                    if (ble.isConnected(fav.mac)) echo("WARNING: still connected to ${fav.mac}", err = true)
                }
            }
        }
    }
}

class CommandLog : CliktCommand(name = "log") {
    private val limit by option(help = "How many").int().default(20)

    override fun help(context: com.github.ajalt.clikt.core.Context) = "Show the log of commands sent to batteries, newest first."

    override fun run() {
        val names = StateStore().load().favorites.associate { it.mac to it.nickname }
        HistoryDb().use { db ->
            val rows = db.commands(limit)
            if (rows.isEmpty()) echo("No commands sent yet.")
            for (c in rows) {
                echo(
                    String.format(
                        Locale.US, "%tF %<tT  %-12s %-12s %-7s %-5s %s",
                        c.t, names[c.mac] ?: c.mac, c.command, c.source, c.node, c.result,
                    ),
                )
            }
        }
    }
}

class Scan : CliktCommand() {
    private val seconds by option(help = "How long to search").int().default(15)
    private val url by option(help = "Hub URL").default("http://127.0.0.1:$DEFAULT_PORT")

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Search for batteries that aren't favorites yet (through the running hub). Add one with add-favorite."

    override fun run() {
        post("$url/api/scan", """{"seconds":$seconds}""")
        echo("Searching for $seconds s…")
        val json = Json { ignoreUnknownKeys = true }
        var state: ScanState
        do {
            Thread.sleep(1000)
            state = json.decodeFromString<ScanState>(get("$url/api/scan"))
        } while (state.scanning)
        state.error?.let { throw CliktError("Search failed: $it") }
        if (state.devices.isEmpty()) echo("No new batteries found (favorites aren't listed).")
        for (d in state.devices) echo(String.format(Locale.US, "  %s  %-18s %4s dBm", d.mac, d.name ?: "?", d.rssiDbm ?: "?"))
    }
}

class ImportPhone : CliktCommand(name = "import-phone") {
    private val adb by option(help = "Path to adb").default("adb")

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Copy favorites, nicknames, groups, and estimate thresholds from the Android app over USB (adb). Hub readings are kept."

    override fun run() {
        val proc = ProcessBuilder(adb, "exec-out", "run-as", "app.batterycommander", "cat", "files/favorites.json")
            .redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0 || !text.trimStart().startsWith("{")) throw CliktError("adb failed: ${text.take(300)}")

        val store = StateStore()
        val phone = store.decode(text)
        val hub = store.load()
        val keep = phone.favorites.map { it.mac }.toSet()
        store.save(
            hub.copy(
                favorites = phone.favorites, groups = phone.groups, totalThresholds = phone.totalThresholds,
                readings = hub.readings.filterKeys { it in keep }, recent = hub.recent.filterKeys { it in keep },
            ),
        )
        echo("Imported ${phone.favorites.size} favorites and ${phone.groups.size} groups into ${HubPaths.stateFile}")
        echo("A running hub picks this up within 30 seconds.")
    }
}

class AddFavorite : CliktCommand(name = "add-favorite") {
    private val mac by argument(help = "Battery MAC, e.g. A5:C2:39:57:78:44")
    private val nickname by argument(help = "Name to show")

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Add (or rename) a favorite without the phone. A running hub picks it up within 30 seconds."

    override fun run() {
        val normalized = mac.uppercase()
        if (!Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(normalized)) throw CliktError("Not a MAC address: $mac")
        val store = StateStore()
        val s = store.load()
        val existing = s.favorites.any { it.mac == normalized }
        store.save(
            s.copy(
                favorites = if (existing) s.favorites.map { if (it.mac == normalized) it.copy(nickname = nickname) else it }
                else s.favorites + Favorite(mac = normalized, nickname = nickname),
            ),
        )
        echo(if (existing) "Renamed $normalized to \"$nickname\"" else "Added $normalized as \"$nickname\"")
    }
}

class Probe : CliktCommand() {
    private val macs by argument(help = "Battery MACs").multiple(required = true)
    private val cycles by option(help = "Times to read each battery").int().default(1)

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Read-only diagnostic: read batteries directly (stop the hub first; one connection per battery)."

    override fun run() = runBlocking {
        BluezBle().use { ble ->
            val reader = BluezReader(ble)
            repeat(cycles) { c ->
                for (mac in macs) {
                    val start = System.currentTimeMillis()
                    runCatching { reader.read(mac) }
                        .onSuccess { r ->
                            echo("cycle ${c + 1} $mac ${r.model} rssi=${r.rssiDbm} soc=${r.stateOfChargePct}% " +
                                "${r.packVoltageMv / 1000.0}V ${r.currentMa / 1000.0}A ${System.currentTimeMillis() - start}ms")
                        }
                        .onFailure { echo("cycle ${c + 1} $mac FAILED: ${it.message}") }
                }
            }
            macs.filter { ble.isConnected(it) }.forEach { echo("STILL CONNECTED: $it") }
        }
    }
}

private val http: HttpClient = HttpClient.newHttpClient()

private class HubUnreachable(url: String) : CliktError("Hub not reachable at $url (is `battery-hub serve` running?)")

private fun get(url: String): String {
    val res = http.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
    if (res.statusCode() != 200) throw CliktError("GET $url → ${res.statusCode()}")
    return res.body()
}

private fun put(url: String, body: String) = send("PUT", url, body)

private fun post(url: String, body: String = "{}") = send("POST", url, body)

private fun send(method: String, url: String, body: String) {
    val req = HttpRequest.newBuilder(URI(url))
        .header("Content-Type", "application/json")
        .header("X-Battery-Hub", "1")
        .method(method, HttpRequest.BodyPublishers.ofString(body))
        .build()
    val res = try {
        http.send(req, HttpResponse.BodyHandlers.ofString())
    } catch (e: Exception) {
        throw HubUnreachable(url)
    }
    if (res.statusCode() !in 200..299) throw CliktError("$method $url → ${res.statusCode()}: ${res.body()}")
}

fun main(args: Array<String>) = Hub().subcommands(Serve(), Status(), Refresh(), Interval(), Thresholds(), Charge(), CommandLog(), Scan(), ImportPhone(), AddFavorite(), Probe()).main(args)
