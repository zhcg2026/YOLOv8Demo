package com.example.citydetect

import android.content.Context
import android.content.SharedPreferences

class ConfigManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("CityDetectConfig", Context.MODE_PRIVATE)

    // 服务器根地址（无末尾 /）；登录页可改，便于 Cloudflare 临时域名或局域网 IP
    var serverUrl: String
        get() = prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString("server_url", value.trimEnd('/')).apply()

    // 展示阈值（后端控制，本地仅用于显示）
    var displayConfidenceThreshold: Float
        get() = prefs.getFloat("display_conf_threshold", 0.50f).coerceIn(0.05f, 0.99f)
        set(value) = prefs.edit().putFloat("display_conf_threshold", value.coerceIn(0.05f, 0.99f)).apply()

    // 上报阈值（后端控制，本地仅用于预判断）
    var reportConfidenceThreshold: Float
        get() = prefs.getFloat("report_conf_threshold", 0.70f).coerceIn(0.05f, 0.99f)
        set(value) = prefs.edit().putFloat("report_conf_threshold", value.coerceIn(0.05f, 0.99f)).apply()

    // 用户登录状态
    var userToken: String
        get() = prefs.getString("user_token", "") ?: ""
        set(value) = prefs.edit().putString("user_token", value).apply()

    var userId: Int
        get() = prefs.getInt("user_id", 0)
        set(value) = prefs.edit().putInt("user_id", value).apply()

    var userName: String
        get() = prefs.getString("user_name", "") ?: ""
        set(value) = prefs.edit().putString("user_name", value).apply()

    var userRole: String
        get() = prefs.getString("user_role", "") ?: ""
        set(value) = prefs.edit().putString("user_role", value).apply()

    fun isLoggedIn(): Boolean {
        return userToken.isNotEmpty() && userId > 0
    }

    fun clearLogin() {
        prefs.edit()
            .remove("user_token")
            .remove("user_id")
            .remove("user_name")
            .remove("user_role")
            .apply()
    }

    companion object {
        /** 未配置过时的默认根地址；临时隧道域名会变，可在登录页修改 */
        private const val DEFAULT_SERVER_URL =
            "https://fully-challenged-touring-literary.trycloudflare.com"

        @Volatile
        private var instance: ConfigManager? = null

        fun getInstance(context: Context): ConfigManager {
            return instance ?: synchronized(this) {
                instance ?: ConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }
}