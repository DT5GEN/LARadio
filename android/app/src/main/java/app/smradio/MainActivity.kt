package app.smradio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import app.smradio.playback.dsp.SpectrumBus
import app.smradio.ui.AppRoot
import app.smradio.ui.PlayerViewModel
import app.smradio.ui.RetroTheme

class MainActivity : ComponentActivity() {
    private val viewModel: PlayerViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* playback works without it */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            RetroTheme { AppRoot(viewModel) }
        }
    }

    override fun onStart() {
        super.onStart()
        SpectrumBus.appForeground = true
    }

    override fun onStop() {
        SpectrumBus.appForeground = false
        super.onStop()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
