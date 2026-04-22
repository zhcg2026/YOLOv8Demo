package com.example.citydetect

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import java.text.SimpleDateFormat
import java.util.*

class TrackHistoryActivity : AppCompatActivity() {

    private lateinit var tracksList: ListView
    private lateinit var emptyText: TextView
    private lateinit var configManager: ConfigManager
    private val client = OkHttpClient.Builder().build()
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    data class TrackListResponse(
        val total: Int,
        val items: List<TrackItem>
    )

    data class TrackItem(
        val id: Int,
        val user_name: String?,
        val start_time: String,
        val end_time: String?,
        val distance_km: Double,
        val duration_min: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_track_history)

        configManager = ConfigManager.getInstance(this)

        tracksList = findViewById(R.id.tracksList)
        emptyText = findViewById(R.id.emptyText)

        loadTracks()
    }

    private fun loadTracks() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url("${configManager.serverUrl}/api/tracks?user_id=${configManager.userId}&page=1&page_size=50")
                    .header("Authorization", "Bearer ${configManager.userToken}")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    val tracksResponse = gson.fromJson(body, TrackListResponse::class.java)
                    val tracks = tracksResponse.items

                    withContext(Dispatchers.Main) {
                        if (tracks.isEmpty()) {
                            emptyText.visibility = TextView.VISIBLE
                            tracksList.visibility = ListView.GONE
                        } else {
                            emptyText.visibility = TextView.GONE
                            tracksList.visibility = ListView.VISIBLE

                            val items = tracks.map { t ->
                                val startStr = try {
                                    val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(t.start_time)
                                    dateFormat.format(date)
                                } catch (e: Exception) { t.start_time }
                                "#${t.id} $startStr | ${t.distance_km}km | ${t.duration_min}分钟"
                            }

                            val adapter = ArrayAdapter(this@TrackHistoryActivity, android.R.layout.simple_list_item_1, items)
                            tracksList.adapter = adapter
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@TrackHistoryActivity, "获取失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TrackHistoryActivity, "获取异常: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}