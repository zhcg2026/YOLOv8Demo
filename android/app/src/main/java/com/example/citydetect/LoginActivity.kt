package com.example.citydetect

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var serverUrlInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginBtn: Button
    private lateinit var configManager: ConfigManager
    private lateinit var authApi: AuthApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        configManager = ConfigManager.getInstance(this)
        authApi = AuthApi(configManager)

        serverUrlInput = findViewById(R.id.serverUrlInput)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        loginBtn = findViewById(R.id.loginBtn)
        serverUrlInput.setText(configManager.serverUrl)

        // 检查是否已登录
        if (configManager.isLoggedIn()) {
            startMainMenu()
            finish()
            return
        }

        loginBtn.setOnClickListener {
            val baseUrl = serverUrlInput.text.toString().trim().trimEnd('/')
            if (baseUrl.isEmpty()) {
                Toast.makeText(this, "请填写服务器地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            configManager.serverUrl = baseUrl

            val username = usernameInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "请输入用户名和密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            loginBtn.isEnabled = false
            loginBtn.text = "登录中..."

            CoroutineScope(Dispatchers.IO).launch {
                val result = authApi.login(username, password)
                withContext(Dispatchers.Main) {
                    loginBtn.isEnabled = true
                    loginBtn.text = "登录"
                    if (result != null) {
                        configManager.userToken = result.token
                        configManager.userId = result.user.id
                        configManager.userName = result.user.name ?: result.user.username
                        configManager.userRole = result.user.role
                        Toast.makeText(this@LoginActivity, "登录成功", Toast.LENGTH_SHORT).show()
                        startMainMenu()
                        finish()
                    } else {
                        Toast.makeText(this@LoginActivity, "登录失败，请检查用户名密码", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun startMainMenu() {
        val intent = Intent(this, MainMenuActivity::class.java)
        startActivity(intent)
    }
}