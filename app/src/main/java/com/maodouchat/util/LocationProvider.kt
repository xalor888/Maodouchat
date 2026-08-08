package com.maodouchat.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

enum class LocationFailure {
    PERMISSION_REQUIRED,
    SERVICES_DISABLED,
    UNAVAILABLE
}

class LocationException(
    val failure: LocationFailure,
    cause: Throwable? = null
) : Exception(cause)

object LocationProvider {
    private const val FRESH_LOCATION_MS = 2L * 60L * 1000L
    private const val REQUEST_TIMEOUT_MS = 12_000L

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(context: Context): Result<Location> = try {
        // Lint cannot prove permission across the suspend boundary; re-check at every privileged call.
        if (!hasLocationPermission(context)) {
            Result.failure(LocationException(LocationFailure.PERMISSION_REQUIRED))
        } else {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            if (providers.isEmpty()) {
                Result.failure(LocationException(LocationFailure.SERVICES_DISABLED))
            } else {
                val cached = providers.mapNotNull { provider ->
                    if (!hasLocationPermission(context)) return@mapNotNull null
                    runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                }.maxByOrNull { it.time }
                if (cached != null && System.currentTimeMillis() - cached.time <= FRESH_LOCATION_MS) {
                    Result.success(cached)
                } else {
                    val live = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                        suspendCancellableCoroutine { continuation ->
                            val listener = object : LocationListener {
                                override fun onLocationChanged(location: Location) {
                                    manager.removeUpdates(this)
                                    if (continuation.isActive) continuation.resume(location)
                                }

                                @Deprecated("Deprecated in Android")
                                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                                override fun onProviderEnabled(provider: String) = Unit
                                override fun onProviderDisabled(provider: String) = Unit
                            }
                            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                            if (!hasLocationPermission(context)) {
                                // No privileged request; outer timeout/cached path handles failure.
                                return@suspendCancellableCoroutine
                            }
                            providers.forEach { provider ->
                                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                            }
                        }
                    }
                    when {
                        live != null -> Result.success(live)
                        cached != null -> Result.success(cached)
                        else -> Result.failure(LocationException(LocationFailure.UNAVAILABLE))
                    }
                }
            }
        }
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: LocationException) {
        Result.failure(error)
    } catch (error: Exception) {
        Result.failure(LocationException(LocationFailure.UNAVAILABLE, error))
    }


    /**
     * Best-effort continuous updates until [cancel] is invoked.
     * Caller owns lifecycle; always remove updates on cancel.
     */
    @SuppressLint("MissingPermission")
    fun requestUpdates(
        context: Context,
        minTimeMs: Long = 8_000L,
        minDistanceM: Float = 8f,
        onLocation: (Location) -> Unit
    ): () -> Unit {
        if (!hasLocationPermission(context)) return {}
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return {}
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onLocation(location)
            }
            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        providers.forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(provider, minTimeMs, minDistanceM, listener, Looper.getMainLooper())
            }
        }
        return {
            runCatching { manager.removeUpdates(listener) }
        }
    }

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
