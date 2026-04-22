package com.example.citydetect

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*

class AuthApi(private val configManager: ConfigManager) {

    private val client = OkHttpClient.Builder().build()
    private val gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss")
        .create()

    private fun getServerUrl(): String = configManager.serverUrl

    suspend fun login(username: String, password: String): LoginResult? {
        return withContext(Dispatchers.IO) {
            try {
                val request = LoginRequest(username, password)
                val json = gson.toJson(request)
                val body = json.toRequestBody("application/json".toMediaType())

                val httpRequest = Request.Builder()
                    .url("${getServerUrl()}/api/auth/login")
                    .post(body)
                    .build()

                val response = client.newCall(httpRequest).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    Log.d("AuthApi", "登录成功: $responseBody")
                    gson.fromJson(responseBody, LoginResult::class.java)
                } else {
                    Log.w("AuthApi", "登录失败: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e("AuthApi", "登录异常", e)
                null
            }
        }
    }

    suspend fun getCurrentUser(): UserInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val httpRequest = Request.Builder()
                    .url("${getServerUrl()}/api/users/me")
                    .header("Authorization", "Bearer ${configManager.userToken}")
                    .get()
                    .build()

                val response = client.newCall(httpRequest).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    gson.fromJson(responseBody, UserInfo::class.java)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("AuthApi", "获取用户信息异常", e)
                null
            }
        }
    }
}

data class LoginRequest(
    val username: String,
    val password: String
)

data class LoginResult(
    val token: String,
    val user: UserInfo
)

data class UserInfo(
    val id: Int,
    val username: String,
    val role: String,
    val name: String?,
    val phone: String?
)