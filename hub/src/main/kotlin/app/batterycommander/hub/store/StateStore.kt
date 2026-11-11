package app.batterycommander.hub.store

import app.batterycommander.domain.StoredState
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where the hub keeps its files: `$XDG_DATA_HOME/battery-commander`, default `~/.local/share/battery-commander`. */
object HubPaths {
    val dataDir: File by lazy {
        val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            ?: (System.getProperty("user.home") + "/.local/share")
        File(base, "battery-commander").apply { mkdirs() }
    }
    val stateFile: File get() = File(dataDir, "state.json")
    val historyDb: File get() = File(dataDir, "history.db")
}

/**
 * The hub's favorites, groups, and last readings, as JSON in the same format the phone uses
 * (StoredState). Written atomically; a corrupt file is set aside rather than crashing the hub.
 */
class StateStore(private val file: File = HubPaths.stateFile) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false }

    /** Modification time of the file after our last read or write; a newer one means an external edit. */
    @Volatile
    var lastSeenModified: Long = 0
        private set

    @Synchronized
    fun load(): StoredState = try {
        if (file.exists()) {
            json.decodeFromString<StoredState>(file.readText()).also { lastSeenModified = file.lastModified() }
        } else {
            StoredState()
        }
    } catch (e: Exception) {
        file.renameTo(File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis()))
        StoredState()
    }

    @Synchronized
    fun save(state: StoredState) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(state))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        lastSeenModified = file.lastModified()
    }

    /** True if something else (e.g. `battery-hub import-phone`) changed the file since we last touched it. */
    fun changedExternally(): Boolean = file.exists() && file.lastModified() > lastSeenModified

    fun decode(text: String): StoredState = json.decodeFromString(text)
}
