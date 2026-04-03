package com.example.screenmirror

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.screenmirror.ui.screens.MainScreen
import com.example.screenmirror.ui.theme.ScreenMirrorTheme
import com.example.screenmirror.ui.viewmodel.MirroringViewModel

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.entries.forEach { (perm, granted) ->
            if (!granted) android.util.Log.w("MainActivity", "Permission denied: $perm")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestDiscoveryPermissions()

        val factory = viewModelFactory {
            initializer {
                // applicationContext is safe here — MediaRouter is gone,
                // NSD and WifiP2p both work fine with applicationContext.
                MirroringViewModel(applicationContext)
            }
        }

        val viewModel = ViewModelProvider(this, factory)[MirroringViewModel::class.java]

        setContent {
            ScreenMirrorTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    private fun requestDiscoveryPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // Wi-Fi P2P (Miracast) requires CHANGE_WIFI_STATE — always needed
        permissions.add(Manifest.permission.CHANGE_WIFI_STATE)

        // Android 12+ Bluetooth permissions (still useful if device also supports BT display)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // Android 13+: NEARBY_WIFI_DEVICES replaces location for Wi-Fi P2P scans
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        requestPermissionLauncher.launch(permissions.toTypedArray())
    }
}
