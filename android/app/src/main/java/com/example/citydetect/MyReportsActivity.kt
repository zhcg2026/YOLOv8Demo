package com.example.citydetect

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MyReportsActivity : AppCompatActivity() {

    private lateinit var reportsList: ListView
    private lateinit var emptyText: TextView
    private lateinit var configManager: ConfigManager
    private lateinit var reportApi: ReportApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_reports)

        configManager = ConfigManager.getInstance(this)
        reportApi = ReportApi(configManager)

        reportsList = findViewById(R.id.reportsList)
        emptyText = findViewById(R.id.emptyText)

        loadReports()
    }

    private fun loadReports() {
        CoroutineScope(Dispatchers.IO).launch {
            val reports = reportApi.getMyReports()
            withContext(Dispatchers.Main) {
                if (reports == null) {
                    Toast.makeText(this@MyReportsActivity, "获取失败", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                if (reports.isEmpty()) {
                    emptyText.visibility = TextView.VISIBLE
                    reportsList.visibility = ListView.GONE
                } else {
                    emptyText.visibility = TextView.GONE
                    reportsList.visibility = ListView.VISIBLE

                    val items = reports.map { r ->
                        val statusText = when (r.status) {
                            "pending" -> "待审核"
                            "confirmed" -> "已确认"
                            "rejected" -> "已作废"
                            else -> r.status
                        }
                        "#${r.id} ${r.problem_type} (${(r.confidence * 100).toInt()}%) [$statusText]"
                    }

                    val adapter = ArrayAdapter(this@MyReportsActivity, android.R.layout.simple_list_item_1, items)
                    reportsList.adapter = adapter
                }
            }
        }
    }
}