package app.batterycommander.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.batterycommander.BatteryApp
import app.batterycommander.ble.AndroidBle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as BatteryApp

        // Poll while the app is on screen. Background monitoring (foreground service) is a later milestone.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { app.repository.pollWhileActive() }
        }

        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PermissionGate(app.ble) { App(app) }
                }
            }
        }
    }
}

@Composable
private fun App(app: BatteryApp) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val mac = selected
    if (mac == null) {
        OverviewScreen(app.repository, onOpen = { selected = it })
    } else {
        BackHandler { selected = null }
        DetailScreen(app.repository, mac, onBack = { selected = null })
    }
}

/** Asks for the Bluetooth permissions and for Bluetooth to be on before showing [content]. */
@Composable
private fun PermissionGate(ble: AndroidBle, content: @Composable () -> Unit) {
    var granted by remember { mutableStateOf(ble.hasPermissions()) }
    var enabled by remember { mutableStateOf(ble.isEnabled) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = ble.hasPermissions()
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        enabled = ble.isEnabled
    }
    when {
        !granted -> Prompt(
            "Battery Commander talks to your batteries over Bluetooth. It needs permission to find and connect to nearby devices. It never uses your location or the internet.",
            "Allow Bluetooth",
        ) { permissionLauncher.launch(AndroidBle.PERMISSIONS) }
        !enabled -> Prompt("Bluetooth is off.", "Turn on Bluetooth") {
            enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
        else -> content()
    }
}

@Composable
private fun Prompt(message: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = onAction) { Text(action) }
    }
}
