package nowcast.app

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.SizeF
import android.view.Gravity
import android.widget.FrameLayout
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Debug builds: one real widget, bound to a private host at an exact launcher size, for screenshots.
 * Needs `adb shell appwidget grantbind --package nowcast.app`.
 * `am start -n nowcast.app/.WidgetGallery --es type RADAR --es size MEDIUM [--es theme DARK --es opacity 60 ...]`
 */
class WidgetGallery : Activity() {
    private val host by lazy { AppWidgetHost(this, 4242) }
    private var id = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val type = WidgetType.valueOf(intent.getStringExtra("type") ?: "SUMMARY")
        val (w, h) = SIZES.getValue(SizeClass.valueOf(intent.getStringExtra("size") ?: "MEDIUM"))
        val receiver = Widgets.ALL.first { it.second.defaultType == type }.first
        host.deleteHost()
        id = host.allocateAppWidgetId()
        val mgr = AppWidgetManager.getInstance(this)
        check(mgr.bindAppWidgetIdIfAllowed(id, ComponentName(this, receiver))) { "appwidget grantbind first" }
        val info = mgr.getAppWidgetInfo(id)
        val view = host.createView(applicationContext, id, info)
        view.updateAppWidgetSize(Bundle(), listOf(SizeF(w, h)))
        val d = resources.displayMetrics.density
        setContentView(FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(84, 110, 140), Color.rgb(150, 120, 160), Color.rgb(90, 140, 120)))
            addView(view, FrameLayout.LayoutParams((w * d).toInt(), (h * d).toInt(), Gravity.CENTER))
        })
        // --es ageMin 47: pretend the newest radar frame is that old, to see the stale state
        intent.getStringExtra("ageMin")?.toLongOrNull()?.let { m ->
            Snapshot.read(filesDir)?.let { it.copy(issued = System.currentTimeMillis() / 1000 - 60 * m).write(filesDir) }
        }
        val extras = intent.extras ?: Bundle()
        val opts = WidgetOptions.fromMap(extras.keySet().associateWith { extras.getString(it) }, type)
        MainScope().launch {
            WidgetConfigActivity.save(this@WidgetGallery, GlanceAppWidgetManager(this@WidgetGallery).getGlanceIdBy(id), Widgets.forAppWidget(this@WidgetGallery, id), opts)
            // --es configure 1: open the configuration screen for this widget, as a launcher would
            if (intent.hasExtra("configure")) startActivity(android.content.Intent(this@WidgetGallery, WidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        }
    }

    override fun onStart() { super.onStart(); host.startListening() }
    override fun onStop() { super.onStop(); host.stopListening() }
    override fun onDestroy() { host.deleteAppWidgetId(id); super.onDestroy() }

    companion object {
        /** Pixel launcher sizes on a 411 dp wide phone: 2×1, 4×2, 4×4. */
        val SIZES = mapOf(SizeClass.SMALL to (180f to 80f), SizeClass.MEDIUM to (370f to 190f), SizeClass.LARGE to (370f to 390f))
    }
}
