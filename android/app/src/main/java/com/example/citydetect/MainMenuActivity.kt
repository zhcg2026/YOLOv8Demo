package com.example.citydetect

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainMenuActivity : AppCompatActivity() {

    private lateinit var welcomeText: TextView
    private lateinit var startInspectionBtn: Button
    private lateinit var myReportsBtn: Button
    private lateinit var trackHistoryBtn: Button
    private lateinit var userSettingsBtn: Button
    private lateinit var logoutBtn: Button
    private lateinit var configManager: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_menu)

        configManager = ConfigManager.getInstance(this)

        // 检查登录状态
        if (!configManager.isLoggedIn()) {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        welcomeText = findViewById(R.id.welcomeText)
        startInspectionBtn = findViewById(R.id.startInspectionBtn)
        myReportsBtn = findViewById(R.id.myReportsBtn)
        trackHistoryBtn = findViewById(R.id.trackHistoryBtn)
        userSettingsBtn = findViewById(R.id.userSettingsBtn)
        logoutBtn = findViewById(R.id.logoutBtn)

        // 显示欢迎信息
        val userName = configManager.userName
        welcomeText.text = "欢迎，$userName"

        // 开始巡查
        startInspectionBtn.setOnClickListener {
            val intent = Intent(this, InspectionActivity::class.java)
            startActivity(intent)
        }

        // 我的上报
        myReportsBtn.setOnClickListener {
            val intent = Intent(this, MyReportsActivity::class.java)
            startActivity(intent)
        }

        // 历史轨迹
        trackHistoryBtn.setOnClickListener {
            val intent = Intent(this, TrackHistoryActivity::class.java)
            startActivity(intent)
        }

        // 用户设置
        userSettingsBtn.setOnClickListener {
            showUserSettings()
        }

        // 退出登录
        logoutBtn.setOnClickListener {
            configManager.clearLogin()
            Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    private fun showUserSettings() {
        val info = "用户名: ${configManager.userName}\n角色: ${configManager.userRole}"
        AlertDialog.Builder(this)
            .setTitle("用户信息")
            .setMessage(info)
            .setNeutralButton("服务器地址") { _, _ -> showServerUrlDialog() }
            .setPositiveButton("确定", null)
            .show()
    }

    /** 已登录时也可改穿透/内网根地址，勿带末尾斜杠 */
    private fun showServerUrlDialog() {
        val input = EditText(this).apply {
            setText(configManager.serverUrl)
            hint = "https://xxxx.trycloudflare.com"
        }
        AlertDialog.Builder(this)
            .setTitle("服务器根地址")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val url = input.text.toString().trim().trimEnd('/')
                if (url.isEmpty()) {
                    Toast.makeText(this, "地址不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    configManager.serverUrl = url
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}