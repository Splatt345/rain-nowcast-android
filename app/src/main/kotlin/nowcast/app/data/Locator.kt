package nowcast.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs

/** Where the phone is: precise or approximate, whichever the user allowed; balanced power, never GPS-only tracking. */
object Locator {
    /** About 500 m: less is noise next to a 1 km radar pixel, and not worth a new cycle. */
    const val MOVED_DEG = 0.005
    private const val FRESH_MS = 10 * 60_000L

    fun canLocate(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) || isPrecise(ctx)

    /** Approximate-only apps get at most one update per 10 min from Android, whatever they ask for. */
    fun isPrecise(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    /** Android 10+ keeps location from a background worker unless "Allow all the time" is granted. */
    fun canInBackground(ctx: Context) = Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun moved(home: Pair<Double, Double>, lat: Double, lon: Double) =
        abs(home.first - lat) > MOVED_DEG || abs(home.second - lon) > MOVED_DEG

    /** A fix from the last 10 min that any app already paid for, else one fresh network fix (20 s at most). */
    @SuppressLint("MissingPermission")
    suspend fun fix(ctx: Context): Location? {
        if (!canLocate(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        val recent = (if (Build.VERSION.SDK_INT >= 31) listOf(LocationManager.FUSED_PROVIDER) else emptyList())
            .plus(listOf(LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER))
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (recent != null && System.currentTimeMillis() - recent.time < FRESH_MS) return recent
        val provider = provider(lm) ?: return recent
        if (Build.VERSION.SDK_INT < 30) return recent
        return withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<Location?> { c ->
                val cancel = CancellationSignal()
                c.invokeOnCancellation { cancel.cancel() }
                try { lm.getCurrentLocation(provider, cancel, ctx.mainExecutor) { if (c.isActive) c.resume(it) } }
                catch (e: Exception) { if (c.isActive) c.resume(null) }
            }
        } ?: recent
    }

    /**
     * Live fixes while someone collects (the app on screen): at most one a minute, only after 300 m.
     * High accuracy, so GPS may run: it is on-screen only, like a map app, and balanced power gives
     * nothing where there is no Wi-Fi or cell location (outdoors, the emulator).
     */
    @SuppressLint("MissingPermission")
    fun updates(ctx: Context): Flow<Location> = callbackFlow {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val provider = if (canLocate(ctx)) provider(lm) else null
        if (provider == null) { close(); return@callbackFlow }
        val l = LocationListener { trySend(it) }
        runCatching {
            if (Build.VERSION.SDK_INT >= 31)
                lm.requestLocationUpdates(provider, LocationRequest.Builder(60_000L).setMinUpdateDistanceMeters(300f)
                    .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build(), ctx.mainExecutor, l)
            else lm.requestLocationUpdates(provider, 60_000L, 300f, l, Looper.getMainLooper())
        }.onFailure { close() }
        awaitClose { lm.removeUpdates(l) }
    }

    /** Fused (Android 12+) mixes Wi-Fi, cell and GPS at balanced power; before that, network, then GPS. */
    private fun provider(lm: LocationManager) =
        (if (Build.VERSION.SDK_INT >= 31) listOf(LocationManager.FUSED_PROVIDER) else emptyList())
            .plus(listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER))
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
}
