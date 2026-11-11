package app.batterycommander.hub.store

import app.batterycommander.domain.BatteryReading
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** One point on a history chart. */
@Serializable
data class HistoryPoint(
    val t: Long,
    val soc: Int,
    val voltageMv: Int,
    val currentMa: Int,
    val tempDeciC: Int?,
)

/** One state-changing command sent to a battery (FR-073, docs/safety.md rule 7). */
@Serializable
data class CommandRecord(
    val t: Long,
    val mac: String,
    /** e.g. "charge off". */
    val command: String,
    /** "user" (CLI or UI) or "profile" (an automatic charge limit). */
    val source: String,
    /** "hub" or "phone". */
    val node: String,
    /** "ok", or the error. */
    val result: String,
)

/**
 * Every polled reading, in SQLite. The common chart fields are columns; the full reading is kept
 * as JSON so nothing is lost if the schema grows. At 5 batteries every 10 minutes that's about
 * 260k rows a year, so no downsampling yet.
 */
class HistoryDb(file: File = HubPaths.historyDb) : AutoCloseable {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    private val json = Json

    init {
        conn.createStatement().use {
            it.executeUpdate("PRAGMA journal_mode=WAL")
            it.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS readings (
                    mac TEXT NOT NULL,
                    ts INTEGER NOT NULL,
                    soc INTEGER NOT NULL,
                    voltage_mv INTEGER NOT NULL,
                    current_ma INTEGER NOT NULL,
                    temp_deci_c INTEGER,
                    rssi_dbm INTEGER,
                    reading_json TEXT NOT NULL,
                    PRIMARY KEY (mac, ts)
                )
                """.trimIndent(),
            )
            it.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS commands (
                    ts INTEGER NOT NULL,
                    mac TEXT NOT NULL,
                    command TEXT NOT NULL,
                    source TEXT NOT NULL,
                    node TEXT NOT NULL,
                    result TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    @Synchronized
    fun recordCommand(c: CommandRecord) {
        conn.prepareStatement("INSERT INTO commands (ts, mac, command, source, node, result) VALUES (?,?,?,?,?,?)").use { st ->
            st.setLong(1, c.t)
            st.setString(2, c.mac)
            st.setString(3, c.command)
            st.setString(4, c.source)
            st.setString(5, c.node)
            st.setString(6, c.result)
            st.executeUpdate()
        }
    }

    /** Most recent first. */
    @Synchronized
    fun commands(limit: Int = 50): List<CommandRecord> =
        conn.prepareStatement("SELECT ts, mac, command, source, node, result FROM commands ORDER BY ts DESC, rowid DESC LIMIT ?").use { st ->
            st.setInt(1, limit)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(CommandRecord(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)))
                    }
                }
            }
        }

    @Synchronized
    fun record(mac: String, r: BatteryReading) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO readings (mac, ts, soc, voltage_mv, current_ma, temp_deci_c, rssi_dbm, reading_json) VALUES (?,?,?,?,?,?,?,?)",
        ).use { st ->
            st.setString(1, mac)
            st.setLong(2, r.timestampMs)
            st.setInt(3, r.stateOfChargePct)
            st.setInt(4, r.packVoltageMv)
            st.setInt(5, r.currentMa)
            r.temperaturesDeciC.firstOrNull()?.let { st.setInt(6, it) } ?: st.setNull(6, java.sql.Types.INTEGER)
            r.rssiDbm?.let { st.setInt(7, it) } ?: st.setNull(7, java.sql.Types.INTEGER)
            st.setString(8, json.encodeToString(r))
            st.executeUpdate()
        }
    }

    @Synchronized
    fun history(mac: String, sinceMs: Long): List<HistoryPoint> =
        conn.prepareStatement(
            "SELECT ts, soc, voltage_mv, current_ma, temp_deci_c FROM readings WHERE mac = ? AND ts >= ? ORDER BY ts",
        ).use { st ->
            st.setString(1, mac)
            st.setLong(2, sinceMs)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            HistoryPoint(
                                t = rs.getLong(1),
                                soc = rs.getInt(2),
                                voltageMv = rs.getInt(3),
                                currentMa = rs.getInt(4),
                                tempDeciC = rs.getInt(5).takeUnless { rs.wasNull() },
                            ),
                        )
                    }
                }
            }
        }

    @Synchronized
    override fun close() = conn.close()
}
