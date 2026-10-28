package nowcast.app

import android.app.Application
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

class App : Application() {
    lateinit var repo: Repo
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        repo = Repo(this)
        Notifier.channel(this)
        RefreshWorker.schedule(this, repo.prefs.value.background)
        // generated picker previews (Android 15+) are rate limited: once per install or app version
        val sp = getSharedPreferences("previews", MODE_PRIVATE)
        if (sp.getInt("version", 0) != BuildConfig.VERSION_CODE) MainScope().launch {
            Widgets.publishPreviews(this@App); sp.edit().putInt("version", BuildConfig.VERSION_CODE).apply()
        }
    }
}
