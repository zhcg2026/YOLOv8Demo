package com.example.citydetect

import android.content.Context
import android.content.SharedPreferences

class ConfigManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("CityDetectConfig", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", "http://192.168.0.12:8000") ?: "http://192.168.0.12:8000"
        set(value) = prefs.edit().putString("server_url", value).apply()

    /**
     * 画框展示阈值：影响屏幕上是否显示检测框（建议略低，便于观察）
     */
    var displayConfidenceThreshold: Float
        get() = prefs.getFloat("display_conf_threshold", 0.50f).coerceIn(0.05f, 0.99f)
        set(value) = prefs.edit().putFloat("display_conf_threshold", value.coerceIn(0.05f, 0.99f)).apply()

    /**
     * 上报阈值：影响是否触发上报（建议略高，减少误报/无效上报）
     */
    var reportConfidenceThreshold: Float
        get() = prefs.getFloat("report_conf_threshold", 0.70f).coerceIn(0.05f, 0.99f)
        set(value) = prefs.edit().putFloat("report_conf_threshold", value.coerceIn(0.05f, 0.99f)).apply()

    companion object {
        @Volatile
        private var instance: ConfigManager? = null

        fun getInstance(context: Context): ConfigManager {
            return instance ?: synchronized(this) {
                instance ?: ConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }
}