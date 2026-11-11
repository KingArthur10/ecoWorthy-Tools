package app.batterycommander

import android.app.Application
import app.batterycommander.ble.AndroidBle
import app.batterycommander.data.BatteryRepository
import app.batterycommander.data.FavoritesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File

class BatteryApp : Application() {
    /** App-wide scope: BLE work and saves outlive any one screen. */
    private val appScope = CoroutineScope(SupervisorJob())

    lateinit var ble: AndroidBle
        private set
    lateinit var repository: BatteryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        ble = AndroidBle(this)
        repository = BatteryRepository(ble, FavoritesStore(File(filesDir, "favorites.json")), appScope)
    }
}
