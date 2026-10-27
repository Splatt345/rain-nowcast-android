package nowcast.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nowcast.app.ui.*
import java.time.Instant

enum class Tab { NOW, MAP, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as App).repo
        setContent { NowcastTheme { Root(repo) } }
    }
}

@Composable
private fun Root(repo: Repo) {
    val keys by repo.secrets.sources.collectAsState()
    val settings by repo.prefs.settings.collectAsState()
    val ready = Repo.SOURCE_OPERA in keys || (BuildConfig.DEBUG && settings.demo)
    AnimatedContent(ready, label = "root", transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }) { ok ->
        if (!ok) FirstRun(
            onKey = { repo.secrets.set(Repo.SOURCE_OPERA, it) },
            onDemo = if (BuildConfig.DEBUG) ({ repo.prefs.update { s -> s.copy(demo = true) } }) else null,
        ) else Main(repo)
    }
}

@Composable
private fun Main(repo: Repo) {
    val state by repo.state.collectAsState()
    val settings by repo.prefs.settings.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.NOW) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // foreground refresh every 5 min while visible; WorkManager covers the rest
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { repo.refresh(); delay(5 * 60_000L) }
        }
    }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = Instant.now() } }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(settings.notify) {
        if (settings.notify && Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    NavigationSuiteScaffold(navigationSuiteItems = {
        item(tab == Tab.NOW, { tab = Tab.NOW }, { Icon(if (tab == Tab.NOW) Icons.Filled.WaterDrop else Icons.Outlined.WaterDrop, null) }, label = { Text("Now") })
        item(tab == Tab.MAP, { tab = Tab.MAP }, { Icon(if (tab == Tab.MAP) Icons.Filled.Map else Icons.Outlined.Map, null) }, label = { Text("Map") })
        item(tab == Tab.SETTINGS, { tab = Tab.SETTINGS }, { Icon(if (tab == Tab.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings, null) }, label = { Text("Settings") })
    }) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            when (tab) {
                Tab.NOW -> NowScreen(state, now, settings.view, { v -> repo.prefs.update { it.copy(view = v) } },
                    { scope.launch { repo.refresh(force = true) } }, { tab = Tab.SETTINGS },
                    Modifier.statusBarsPadding())
                Tab.MAP -> MapScreen(repo, state, now)
                Tab.SETTINGS -> SettingsScreen(repo, Modifier.statusBarsPadding())
            }
        }
    }
}
