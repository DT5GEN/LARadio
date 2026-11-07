package app.smradio

import android.app.Application
import android.content.Context
import app.smradio.data.CatalogRepository
import app.smradio.data.Prefs
import app.smradio.data.SettingsController
import app.smradio.data.SleepTimer
import app.smradio.playback.NetworkMonitor
import app.smradio.playback.dsp.EqController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency container (the project is small, no DI framework needed). */
class AppContainer(context: Context) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    val catalogRepo = CatalogRepository(context.applicationContext, http)
    val prefs = Prefs(context.applicationContext)
    val eq = EqController().also { controller ->
        // tiny DataStore read, needed so the first audio buffer already uses the saved equalizer
        val saved = runBlocking { prefs.eq.first() }
        controller.update(saved.enabled, saved.gains)
    }
    val settings = SettingsController(runBlocking { prefs.settings.first() })
    val sleep = SleepTimer()
    val net = NetworkMonitor(context.applicationContext)

    companion object {
        const val USER_AGENT = "SantaMonicaRadio/0.1"
    }
}

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
