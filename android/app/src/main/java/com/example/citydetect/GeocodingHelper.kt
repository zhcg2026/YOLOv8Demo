package com.example.citydetect

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class GeocodingHelper(private val context: Context) {

    private val client = OkHttpClient()

    /**
     * 逆地理编码获取中文地址：
     * 1) 优先用系统 Geocoder（不依赖 OSM，可在部分网络环境更稳定）
     * 2) 失败则回退到 Nominatim (OpenStreetMap)
     */
    suspend fun getAddress(latitude: Double, longitude: Double): String? {
        return withContext(Dispatchers.IO) {
            try {
                val fromGeocoder = getAddressFromAndroidGeocoder(latitude, longitude)
                if (!fromGeocoder.isNullOrBlank()) {
                    return@withContext fromGeocoder
                }
            } catch (e: Exception) {
                Log.w("Geocoding", "Android Geocoder失败，回退OSM: ${e.message}")
            }

            try {
                val url = "https://nominatim.openstreetmap.org/reverse?" +
                    "lat=$latitude&lon=$longitude&format=json&accept-language=zh&zoom=18&addressdetails=1"

                val request = Request.Builder()
                    .url(url)
                    // Nominatim 会对无/弱UA的请求做限制；加 Referer 也更稳一些
                    .header("User-Agent", "CityDetectApp/1.0 (android)")
                    .header("Referer", "https://example.com")
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val json = JSONObject(body)
                        val display = json.optString("display_name")
                        if (display.isNullOrBlank()) null else display
                    } else null
                } else {
                    Log.e("Geocoding", "请求失败: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e("Geocoding", "逆地理编码失败", e)
                null
            }
        }
    }

    private suspend fun getAddressFromAndroidGeocoder(latitude: Double, longitude: Double): String? {
        if (!Geocoder.isPresent()) return null

        val geocoder = Geocoder(context, Locale.SIMPLIFIED_CHINESE)

        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                    val first = addresses?.firstOrNull()
                    continuation.resume(formatAddress(first))
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val addresses: List<Address>? = geocoder.getFromLocation(latitude, longitude, 1)
            formatAddress(addresses?.firstOrNull())
        }
    }

    private fun formatAddress(address: Address?): String? {
        if (address == null) return null
        // 优先完整地址行；否则拼接常用字段
        val line = runCatching { address.getAddressLine(0) }.getOrNull()
        if (!line.isNullOrBlank()) return line

        val parts = listOfNotNull(
            address.adminArea,
            address.subAdminArea,
            address.locality,
            address.subLocality,
            address.thoroughfare,
            address.subThoroughfare,
            address.featureName
        ).filter { it.isNotBlank() }

        return parts.joinToString("").ifBlank { null }
    }
}