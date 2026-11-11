package app.batterycommander.data

import app.batterycommander.domain.BatteryReading
import app.batterycommander.domain.Favorite
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Everything persisted for now: favorites (in display order) and each one's last reading. */
@Serializable
data class StoredState(
    val version: Int = 1,
    val favorites: List<Favorite> = emptyList(),
    val readings: Map<String, BatteryReading> = emptyMap(),
)

/**
 * JSON file in app-private storage. Small enough to rewrite whole on each change; history
 * (FR-070) will need a real database.
 */
class FavoritesStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): StoredState = try {
        if (file.exists()) json.decodeFromString<StoredState>(file.readText()) else StoredState()
    } catch (e: Exception) {
        // A corrupt file must not brick the app; keep it for inspection and start fresh.
        file.renameTo(File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis()))
        StoredState()
    }

    fun save(state: StoredState) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(state))
        if (!tmp.renameTo(file)) throw IllegalStateException("Could not replace ${file.path}")
    }
}
