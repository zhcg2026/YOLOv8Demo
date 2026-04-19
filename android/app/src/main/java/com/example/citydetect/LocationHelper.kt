package com.example.citydetect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class LocationHelper(
    private val context: Context,
    private val onLocationUpdate: (Double, Double) -> Unit
) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private lateinit var locationCallback: LocationCallback

    private var currentLocation: Location? = null
    private var hasLocation = false

    fun startLocationUpdates() {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val gpsEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val netEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        Log.d(
            "LocationHelper",
            "权限状态: FINE=$fineLocationGranted, COARSE=$coarseLocationGranted, GPS=$gpsEnabled, NET=$netEnabled"
        )

        if (!fineLocationGranted && !coarseLocationGranted) {
            Log.e("LocationHelper", "没有定位权限")
            return
        }

        // 先获取最后已知位置
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                Log.d("LocationHelper", "lastLocation结果: $location")
                if (location != null) {
                    currentLocation = location
                    hasLocation = true
                    onLocationUpdate(location.latitude, location.longitude)
                    Log.d("LocationHelper", "使用lastLocation: ${location.latitude}, ${location.longitude}")
                } else {
                    Log.d("LocationHelper", "lastLocation为null，等待实时位置更新")
                }
            }.addOnFailureListener { e ->
                Log.e("LocationHelper", "获取lastLocation失败", e)
            }
        } catch (e: Exception) {
            Log.e("LocationHelper", "获取最后位置异常", e)
        }

        // 实时位置更新
        val priority = if (fineLocationGranted) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val locationRequest = LocationRequest.Builder(
            priority,
            2000L // 2秒更新一次
        ).setMinUpdateIntervalMillis(1000L)
            // 室内/弱信号下 true 可能导致一直“等更准的位置”，表现为长期拿不到回调。
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    currentLocation = location
                    hasLocation = true
                    onLocationUpdate(location.latitude, location.longitude)
                    Log.d("LocationHelper", "实时位置更新: ${location.latitude}, ${location.longitude}, accuracy=${location.accuracy}")
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                context.mainLooper
            )
            Log.d("LocationHelper", "位置更新请求已发起")
        } catch (e: Exception) {
            Log.e("LocationHelper", "定位请求失败", e)
        }
    }

    fun hasLocation(): Boolean = hasLocation

    fun stopLocationUpdates() {
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    fun getCurrentLocation(): Location? {
        Log.d("LocationHelper", "getCurrentLocation: hasLocation=$hasLocation, location=$currentLocation")
        return currentLocation
    }

    suspend fun getCurrentLocationFresh(): Location? = suspendCancellableCoroutine { continuation ->
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineLocationGranted && !coarseLocationGranted) {
            Log.e("LocationHelper", "getCurrentLocationFresh: 没有定位权限")
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val gpsEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val netEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        Log.d("LocationHelper", "getCurrentLocationFresh: provider状态 GPS=$gpsEnabled, NET=$netEnabled")

        val priority = if (fineLocationGranted) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        fun resumeOnce(location: Location?) {
            if (!continuation.isActive) return
            if (location != null) {
                currentLocation = location
                hasLocation = true
                onLocationUpdate(location.latitude, location.longitude)
            }
            continuation.resume(location)
        }

        // 先尝试系统缓存（不依赖 GMS，可在无 Google 服务设备上工作）
        val lmLast = runCatching {
            val gps = if (gpsEnabled) locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null
            val net = if (netEnabled) locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) else null
            listOfNotNull(gps, net).maxByOrNull { it.time }
        }.getOrNull()
        if (lmLast != null) {
            Log.d("LocationHelper", "getCurrentLocationFresh: 使用LocationManager lastKnown")
            resumeOnce(lmLast)
            return@suspendCancellableCoroutine
        }

        try {
            fusedLocationClient.getCurrentLocation(priority, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        Log.d("LocationHelper", "getCurrentLocationFresh(Fused)成功: ${location.latitude}, ${location.longitude}")
                        resumeOnce(location)
                    } else {
                        Log.w("LocationHelper", "getCurrentLocationFresh(Fused)返回null，尝试LocationManager单次定位")
                        // Fused 返回 null 时，回退到 LocationManager 单次定位（GPS/NETWORK）
                        requestSingleUpdateFromLocationManager(
                            fineLocationGranted = fineLocationGranted,
                            gpsEnabled = gpsEnabled,
                            netEnabled = netEnabled,
                            onResult = { loc -> resumeOnce(loc ?: currentLocation) }
                        )
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("LocationHelper", "getCurrentLocationFresh失败", e)
                    requestSingleUpdateFromLocationManager(
                        fineLocationGranted = fineLocationGranted,
                        gpsEnabled = gpsEnabled,
                        netEnabled = netEnabled,
                        onResult = { loc -> resumeOnce(loc ?: currentLocation) }
                    )
                }
        } catch (e: Exception) {
            Log.e("LocationHelper", "getCurrentLocationFresh异常", e)
            requestSingleUpdateFromLocationManager(
                fineLocationGranted = fineLocationGranted,
                gpsEnabled = gpsEnabled,
                netEnabled = netEnabled,
                onResult = { loc -> resumeOnce(loc ?: currentLocation) }
            )
        }
    }

    private fun requestSingleUpdateFromLocationManager(
        fineLocationGranted: Boolean,
        gpsEnabled: Boolean,
        netEnabled: Boolean,
        onResult: (Location?) -> Unit
    ) {
        if (!gpsEnabled && !netEnabled) {
            Log.w("LocationHelper", "LocationManager不可用：GPS/NET均关闭")
            onResult(null)
            return
        }

        val provider = when {
            fineLocationGranted && gpsEnabled -> LocationManager.GPS_PROVIDER
            netEnabled -> LocationManager.NETWORK_PROVIDER
            else -> LocationManager.PASSIVE_PROVIDER
        }

        val handler = Handler(Looper.getMainLooper())
        var finished = false

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (finished) return
                finished = true
                Log.d("LocationHelper", "LocationManager单次定位成功: ${location.latitude}, ${location.longitude}, provider=$provider")
                runCatching { locationManager.removeUpdates(this) }
                onResult(location)
            }
        }

        handler.postDelayed({
            if (finished) return@postDelayed
            finished = true
            Log.w("LocationHelper", "LocationManager单次定位超时")
            runCatching { locationManager.removeUpdates(listener) }
            onResult(null)
        }, 2500L)

        try {
            // requestSingleUpdate 在部分机型上不稳定，这里用 requestLocationUpdates + 立刻拿到一次就 remove
            locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            Log.d("LocationHelper", "LocationManager单次定位已发起: provider=$provider")
        } catch (e: Exception) {
            Log.e("LocationHelper", "LocationManager单次定位发起失败", e)
            runCatching { locationManager.removeUpdates(listener) }
            onResult(null)
        }
    }
}