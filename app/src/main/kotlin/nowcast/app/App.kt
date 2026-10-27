package nowcast.app

import android.app.Application
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
    }
}
