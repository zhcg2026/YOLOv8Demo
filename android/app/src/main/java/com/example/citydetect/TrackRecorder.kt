package com.example.citydetect

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class TrackRecorder(
    private val context: Context,
    private val configManager: ConfigManager
) {

    private val client = OkHttpClient.Builder().build()
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    private val trackPoints = mutableListOf<TrackPointData>()
    private var startTime: Date? = null
    private var isRecording = false
    private var lastRecordTime = 0L
    private val recordIntervalMs = 3000L  // 3秒采样一次，避免短时巡查里程长期为0
    private val minSegmentMeters = 0.8    // 小于0.8米视为定位漂移
    private val maxSegmentSpeedKmh = 120.0 // 过滤明显异常跳点
    private val maxRecordAccuracyMeters = 35.0f // 定位精度大于35米时不记录轨迹点

    data class TrackPointData(
        val latitude: Double,
        val longitude: Double,
        val time: String
    )

    data class TrackUploadRequest(
        val start_time: String,
        val end_time: String?,
        val points: List<TrackPointData>
    )

    fun startRecording() {
        if (isRecording) return
        isRecording = true
        startTime = Date()
        trackPoints.clear()
        lastRecordTime = 0L
        Log.d("TrackRecorder", "开始轨迹记录")
    }

    fun recordPoint(latitude: Double, longitude: Double, accuracyMeters: Float?): String {
        if (!isRecording) return "NOT_RECORDING"

        val now = System.currentTimeMillis()
        if (accuracyMeters != null && accuracyMeters > maxRecordAccuracyMeters) {
            Log.d(
                "TrackRecorder",
                "忽略低精度轨迹点: accuracy=${"%.1f".format(accuracyMeters)}m, threshold=${maxRecordAccuracyMeters}m"
            )
            return "SKIP_LOW_ACCURACY"
        }
        val point = TrackPointData(
            latitude = latitude,
            longitude = longitude,
            time = dateFormat.format(Date())
        )
        if (trackPoints.isEmpty()) {
            lastRecordTime = now
            trackPoints.add(point)
            Log.d("TrackRecorder", "记录首个轨迹点: lat=$latitude, lng=$longitude")
            return "RECORDED_FIRST"
        }
        if (now - lastRecordTime < recordIntervalMs) {
            return "SKIP_INTERVAL"
        }

        val lastPoint = trackPoints.last()
        val segmentKm = haversine(lastPoint.latitude, lastPoint.longitude, latitude, longitude)
        val segmentMeters = segmentKm * 1000.0
        val elapsedHours = ((now - lastRecordTime).toDouble() / 1000.0) / 3600.0
        val segmentSpeedKmh = if (elapsedHours > 0) segmentKm / elapsedHours else 0.0

        if (segmentMeters < minSegmentMeters) {
            Log.d("TrackRecorder", "忽略漂移点: segment=${"%.2f".format(segmentMeters)}m")
            return "SKIP_DRIFT"
        }
        if (segmentSpeedKmh > maxSegmentSpeedKmh) {
            Log.w("TrackRecorder", "忽略异常跳点: speed=${"%.1f".format(segmentSpeedKmh)}km/h")
            return "SKIP_ABNORMAL_SPEED"
        }

        lastRecordTime = now
        trackPoints.add(point)
        Log.d("TrackRecorder", "记录轨迹点: lat=$latitude, lng=$longitude, 共${trackPoints.size}个点")
        return "RECORDED"
    }

    fun stop() {
        isRecording = false
        Log.d("TrackRecorder", "停止轨迹记录")
    }

    fun stopAndUpload() {
        if (!isRecording) return
        isRecording = false

        val endTime = Date()
        val start = startTime

        if (start == null || trackPoints.isEmpty()) {
            Log.d("TrackRecorder", "无轨迹数据，不上传")
            return
        }

        // 计算总距离
        var totalDistance = 0.0
        for (i in 1 until trackPoints.size) {
            val segmentKm = haversine(
                trackPoints[i-1].latitude, trackPoints[i-1].longitude,
                trackPoints[i].latitude, trackPoints[i].longitude
            )
            val segmentMeters = segmentKm * 1000.0
            if (segmentMeters < minSegmentMeters) {
                continue
            }
            val segmentSpeedKmh = calcSegmentSpeedKmh(trackPoints[i - 1], trackPoints[i], segmentKm)
            if (segmentSpeedKmh > maxSegmentSpeedKmh) {
                continue
            }
            totalDistance += segmentKm
        }

        val durationMin = ((endTime.time - start.time) / 60000).toInt()

        Log.d("TrackRecorder", "轨迹统计: ${trackPoints.size}个点, ${totalDistance}km, ${durationMin}分钟")

        // 上传轨迹
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = TrackUploadRequest(
                    start_time = dateFormat.format(start),
                    end_time = dateFormat.format(endTime),
                    points = trackPoints
                )

                val json = gson.toJson(request)
                val body = json.toRequestBody("application/json".toMediaType())

                val httpRequest = Request.Builder()
                    .url("${configManager.serverUrl}/api/tracks")
                    .header("Authorization", "Bearer ${configManager.userToken}")
                    .post(body)
                    .build()

                val response = client.newCall(httpRequest).execute()
                if (response.isSuccessful) {
                    Log.d("TrackRecorder", "轨迹上传成功")
                } else {
                    Log.w("TrackRecorder", "轨迹上传失败: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e("TrackRecorder", "轨迹上传异常", e)
            }
        }
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0  // 地球半径（公里）
        val lat1Rad = lat1 * PI / 180.0
        val lat2Rad = lat2 * PI / 180.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2).pow(2) + cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun calcSegmentSpeedKmh(first: TrackPointData, second: TrackPointData, segmentKm: Double): Double {
        return try {
            val start = dateFormat.parse(first.time)?.time ?: return 0.0
            val end = dateFormat.parse(second.time)?.time ?: return 0.0
            if (end <= start) {
                return 0.0
            }
            val elapsedHours = ((end - start).toDouble() / 1000.0) / 3600.0
            if (elapsedHours <= 0.0) {
                0.0
            } else {
                segmentKm / elapsedHours
            }
        } catch (e: Exception) {
            Log.w("TrackRecorder", "轨迹段速度计算失败: ${e.message}")
            0.0
        }
    }
}