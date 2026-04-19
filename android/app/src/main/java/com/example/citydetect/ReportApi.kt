package com.example.citydetect

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.Unit

class ReportApi(private val configManager: ConfigManager) {

    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private val gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss")
        .create()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    private fun getServerUrl(): String = configManager.serverUrl

    suspend fun report(
        image: Bitmap,
        latitude: Double,
        longitude: Double,
        address: String?,
        problemType: String,
        confidence: Float
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 将图片转为 Base64
                val outputStream = ByteArrayOutputStream()
                image.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                val imageBytes = outputStream.toByteArray()
                val imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

                // 构建请求体
                val request = ReportRequest(
                    image = imageBase64,
                    latitude = latitude,
                    longitude = longitude,
                    address = address,
                    problem_type = problemType,
                    confidence = confidence.toDouble(),
                    detect_time = dateFormat.format(Date()),
                    device_id = "Android-001"
                )

                val json = gson.toJson(request)
                Log.d("ReportApi", "请求内容: $json")
                val body = json.toRequestBody("application/json".toMediaType())

                // 发送请求
                val httpRequest = Request.Builder()
                    .url("${getServerUrl()}/api/reports")
                    .post(body)
                    .build()

                val response = client.newCall(httpRequest).execute()
                Log.d("ReportApi", "响应: ${response.code} - ${response.body?.string()}")
                response.isSuccessful

            } catch (e: Exception) {
                Log.e("ReportApi", "上报失败", e)
                false
            }
        }
    }

    suspend fun getThreshold(): Float? {
        return withContext(Dispatchers.IO) {
            try {
                val httpRequest = Request.Builder()
                    .url("${getServerUrl()}/api/config/threshold")
                    .get()
                    .build()

                val response = client.newCall(httpRequest).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val thresholdResponse = gson.fromJson(body, ThresholdResponse::class.java)
                        Log.d("ReportApi", "获取阈值成功: ${thresholdResponse.value}")
                        thresholdResponse.value.toFloat()
                    } else {
                        null
                    }
                } else {
                    Log.w("ReportApi", "获取阈值失败: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e("ReportApi", "获取阈值异常", e)
                null
            }
        }
    }
}

data class ReportRequest(
    val image: String,
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    val problem_type: String,
    val confidence: Double,
    val detect_time: String,
    val device_id: String
)

data class ThresholdResponse(
    val value: Double,
    val description: String
)