package com.example.citydetect

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.atan2
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: DetectionOverlay
    private lateinit var statusText: TextView
    private lateinit var locationText: TextView
    private lateinit var reportSuccess: TextView
    private lateinit var settingsBtn: ImageButton

    private lateinit var cameraExecutor: ExecutorService
    private var detector: YOLOv8Detector? = null
    private lateinit var locationHelper: LocationHelper
    private lateinit var reportApi: ReportApi
    private lateinit var geocodingHelper: GeocodingHelper
    private lateinit var configManager: ConfigManager

    private var lastReportTime = 0L
    private val reportInterval = 5000L // 5秒内不重复上报
    private val reportMutex = Mutex()
    @Volatile private var reportInProgress = false
    private var lastSuccessfulLatLng: Pair<Double, Double>? = null

    // 去重：记录最近上报的位置+类型
    private data class ReportedItem(
        val latitude: Double,
        val longitude: Double,
        val problemType: String,
        val reportTime: Long
    )
    private val reportedItems = mutableListOf<ReportedItem>()
    private val dedupDistanceMeters = 20.0  // 20米内视为同一位置
    private val dedupTimeMinutes = 5L       // 5分钟内视为重复

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false ||
                              permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (cameraGranted && locationGranted) {
            startCamera()
            locationHelper.startLocationUpdates()
        } else {
            Toast.makeText(this, "需要摄像头和定位权限", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 作业场景保持常亮，避免检测过程中自动息屏。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Log.d("MainActivity", "应用启动")

        // 初始化视图
        previewView = findViewById(R.id.previewView)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        overlayView = findViewById(R.id.overlayView)
        statusText = findViewById(R.id.statusText)
        locationText = findViewById(R.id.locationText)
        reportSuccess = findViewById(R.id.reportSuccess)
        settingsBtn = findViewById(R.id.settingsBtn)

        // 初始化配置
        configManager = ConfigManager.getInstance(this)
        geocodingHelper = GeocodingHelper(this)

        // 初始化组件
        cameraExecutor = Executors.newSingleThreadExecutor()
        locationHelper = LocationHelper(this) { lat, lng ->
            runOnUiThread {
                locationText.text = "位置: %.4f, %.4f".format(lat, lng)
                Log.d("MainActivity", "UI位置更新: $lat, $lng")
            }
            lastSuccessfulLatLng = lat to lng
        }
        reportApi = ReportApi(configManager)

        // 设置按钮点击事件
        settingsBtn.setOnClickListener {
            showSettingsDialog()
        }

        // 延迟初始化检测器（避免阻塞主线程）
        CoroutineScope(Dispatchers.IO).launch {
            try {
                detector = YOLOv8Detector(this@MainActivity)
                val ready = detector?.isReady() == true
                Log.d("MainActivity", "检测器初始化完成，ready=$ready")
                withContext(Dispatchers.Main) {
                    statusText.text = if (ready) "正在检测..." else "模型加载失败"
                }

                // 从服务器获取阈值
                if (ready) {
                    val threshold = reportApi.getThreshold()
                    if (threshold != null) {
                        configManager.reportConfidenceThreshold = threshold
                        Log.d("MainActivity", "服务器阈值已应用: $threshold")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "阈值已同步: %.2f".format(threshold), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "检测器初始化失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    statusText.text = "模型加载失败"
                }
            }
        }

        // 检查权限
        if (checkPermissions()) {
            startCamera()
            locationHelper.startLocationUpdates()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun checkPermissions(): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val locationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return cameraGranted && locationGranted
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // 预览
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // 图像分析
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            // 选择后置摄像头
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e("Camera", "摄像头绑定失败", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        try {
            // 转换为Bitmap
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                val currentDetector = detector
                if (currentDetector == null || !currentDetector.isReady()) {
                    val errorMsg = currentDetector?.getInitError() ?: "检测器初始化中"
                    runOnUiThread {
                        statusText.text = "模型未就绪\n$errorMsg"
                    }
                    return
                }

                // 执行检测（展示阈值：影响画框）
                val displayThreshold = configManager.displayConfidenceThreshold
                val detections = currentDetector.detect(bitmap, displayThreshold)

                // 在主线程更新 UI（将 Bitmap 坐标映射到与 PreviewView 默认 FILL_CENTER 一致的叠加层坐标）
                runOnUiThread {
                    val forOverlay = mapDetectionsBitmapToOverlay(
                        detections,
                        bitmap.width,
                        bitmap.height
                    )
                    overlayView.updateDetections(forOverlay)

                    // 更新状态
                    val detectionInfo = if (detections.isNotEmpty()) {
                        "检测到 ${detections.size} 个目标"
                    } else {
                        "正在检测..."
                    }
                    statusText.text = detectionInfo
                }

                // 发现目标时上报
                val reportThreshold = configManager.reportConfidenceThreshold
                val forReport = detections.filter { it.confidence >= reportThreshold }
                if (forReport.isNotEmpty() && shouldReport()) {
                    reportDetection(bitmap, forReport)
                }
            }
        } finally {
            imageProxy.close()
        }
    }

    /**
     * 检测框在「分析用 Bitmap」像素坐标系中；PreviewView 以等比放大填满屏幕（FILL_CENTER，可能裁切）。
     * 叠加层与预览同尺寸时需做相同变换，否则框会与画面错位。
     */
    private fun mapDetectionsBitmapToOverlay(
        detections: List<Detection>,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): List<Detection> {
        val vw = overlayView.width
        val vh = overlayView.height
        if (vw <= 0 || vh <= 0 || detections.isEmpty()) {
            return detections
        }
        val scale = max(
            vw.toFloat() / bitmapWidth,
            vh.toFloat() / bitmapHeight
        )
        val dx = (vw - bitmapWidth * scale) / 2f
        val dy = (vh - bitmapHeight * scale) / 2f
        return detections.map { d ->
            val r = d.boundingBox
            Detection(
                d.label,
                d.confidence,
                RectF(
                    r.left * scale + dx,
                    r.top * scale + dy,
                    r.right * scale + dx,
                    r.bottom * scale + dy
                )
            )
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val nv21 = yuv420888ToNv21(imageProxy) ?: return null
        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null
        )

        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, imageProxy.width, imageProxy.height),
            90,
            out
        )
        val imageBytes = out.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null

        val matrix = Matrix().apply {
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun yuv420888ToNv21(imageProxy: ImageProxy): ByteArray? {
        if (imageProxy.planes.size < 3) return null

        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        return nv21
    }

    private fun shouldReport(): Boolean {
        val now = System.currentTimeMillis()
        return !reportInProgress && now - lastReportTime > reportInterval
    }

    /**
     * 计算两点之间的距离（米），使用 Haversine 公式
     */
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0  // 地球半径（米）
        val lat1Rad = lat1 * Math.PI / 180.0
        val lat2Rad = lat2 * Math.PI / 180.0
        val dLat = (lat2 - lat1) * Math.PI / 180.0
        val dLon = (lon2 - lon1) * Math.PI / 180.0
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    /**
     * 检查是否为重复上报（同一位置+同一类型）
     */
    private fun isDuplicateReport(latitude: Double, longitude: Double, problemType: String): Boolean {
        val now = System.currentTimeMillis()
        // 清理过期记录
        reportedItems.removeAll { now - it.reportTime > dedupTimeMinutes * 60 * 1000 }
        // 检查是否有相似记录
        return reportedItems.any { item ->
            val dist = distanceMeters(latitude, longitude, item.latitude, item.longitude)
            dist < dedupDistanceMeters && item.problemType == problemType
        }
    }

    /**
     * 记录上报成功
     */
    private fun recordReport(latitude: Double, longitude: Double, problemType: String) {
        reportedItems.add(ReportedItem(latitude, longitude, problemType, System.currentTimeMillis()))
    }

    private fun reportDetection(bitmap: Bitmap, detections: List<Detection>) {
        CoroutineScope(Dispatchers.IO).launch {
            reportMutex.withLock {
                if (reportInProgress) {
                    return@withLock
                }
                reportInProgress = true
            }

            try {
                // 定位短重试 + 超时保护：避免定位接口卡住导致上报流程一直占用。
                var location = withTimeoutOrNull(2500L) {
                    locationHelper.getCurrentLocationFresh()
                } ?: locationHelper.getCurrentLocation()
                var retryCount = 0
                while (location == null && retryCount < 3) {
                    retryCount++
                    delay(1200)
                    location = withTimeoutOrNull(2500L) {
                        locationHelper.getCurrentLocationFresh()
                    } ?: locationHelper.getCurrentLocation()
                    Log.w("Report", "定位重试第${retryCount}次，结果=${if (location != null) "成功" else "失败"}")
                }

                val (latitude, longitude, locationSource) = if (location != null) {
                    Triple(location.latitude, location.longitude, "GPS")
                } else if (lastSuccessfulLatLng != null) {
                    Triple(lastSuccessfulLatLng!!.first, lastSuccessfulLatLng!!.second, "LAST_KNOWN")
                } else {
                    // 极端情况下定位长期不可用时仍上报，先保证事件不丢失，再由后续定位链路优化。
                    Triple(0.0, 0.0, "FALLBACK_ZERO")
                }

                Log.w("Report", "上报位置来源: $locationSource, lat=$latitude, lng=$longitude")

                // 去重检查：同一位置同一类型不重复上报
                val problemType = detections.first().label
                if (isDuplicateReport(latitude, longitude, problemType)) {
                    Log.d("Report", "去重：跳过重复上报 ($problemType @ $latitude, $longitude)")
                    reportInProgress = false
                    return@launch
                }

                // 获取地址信息
                val address = if (locationSource == "FALLBACK_ZERO") {
                    "定位未就绪(0,0)，请检查GPS开关和定位权限"
                } else {
                    withTimeoutOrNull(4500L) {
                        geocodingHelper.getAddress(latitude, longitude)
                    } ?: String.format("纬度%.6f, 经度%.6f", latitude, longitude)
                }
                Log.d("Report", "地址(含兜底): $address")

                lastReportTime = System.currentTimeMillis()
                val annotated = drawDetectionsOnBitmap(bitmap, detections)
                val result = reportApi.report(
                    image = annotated,
                    latitude = latitude,
                    longitude = longitude,
                    address = address,
                    problemType = detections.first().label,
                    confidence = detections.first().confidence
                )
                if (annotated != bitmap) {
                    annotated.recycle()
                }

                withContext(Dispatchers.Main) {
                    if (result) {
                        showReportSuccess()
                        recordReport(latitude, longitude, problemType)
                        Log.d("Report", "上报成功，已记录去重信息")
                    } else {
                        Toast.makeText(this@MainActivity, "上报失败，请检查服务器连接", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("Report", "上报失败", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "上报异常: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                reportInProgress = false
            }
        }
    }

    private fun drawDetectionsOnBitmap(bitmap: Bitmap, detections: List<Detection>): Bitmap {
        if (detections.isEmpty()) return bitmap

        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        val stroke = (max(out.width, out.height) / 320f).coerceIn(3f, 10f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            textSize = (stroke * 6f).coerceIn(26f, 44f)
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            alpha = 180
        }

        detections.forEach { d ->
            paint.color = when (d.label) {
                "单体垃圾" -> Color.RED
                "占道经营" -> Color.BLUE
                else -> Color.GREEN
            }
            bgPaint.color = paint.color

            canvas.drawRect(d.boundingBox, paint)

            val label = "${d.label} ${(d.confidence * 100).toInt()}%"
            val pad = stroke * 2f
            val textW = textPaint.measureText(label)
            val top = (d.boundingBox.top - (textPaint.textSize + pad * 1.2f)).coerceAtLeast(0f)
            val left = d.boundingBox.left.coerceAtLeast(0f)

            canvas.drawRect(
                left,
                top,
                (left + textW + pad * 2f).coerceAtMost(out.width.toFloat()),
                (top + textPaint.textSize + pad).coerceAtMost(out.height.toFloat()),
                bgPaint
            )
            canvas.drawText(label, left + pad, top + textPaint.textSize, textPaint)
        }

        return out
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val serverUrlInput = dialogView.findViewById<EditText>(R.id.serverUrlInput)
        val displayConfInput = dialogView.findViewById<EditText>(R.id.displayConfInput)
        val reportConfInput = dialogView.findViewById<EditText>(R.id.reportConfInput)
        serverUrlInput.setText(configManager.serverUrl)
        displayConfInput.setText(String.format("%.2f", configManager.displayConfidenceThreshold))
        reportConfInput.setText(String.format("%.2f", configManager.reportConfidenceThreshold))

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val newUrl = serverUrlInput.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    configManager.serverUrl = newUrl
                    Toast.makeText(this, "服务器地址已更新", Toast.LENGTH_SHORT).show()
                }

                val displayConf = displayConfInput.text.toString().trim().toFloatOrNull()
                if (displayConf != null) {
                    configManager.displayConfidenceThreshold = displayConf
                }

                val reportConf = reportConfInput.text.toString().trim().toFloatOrNull()
                if (reportConf != null) {
                    configManager.reportConfidenceThreshold = reportConf
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showReportSuccess() {
        reportSuccess.visibility = TextView.VISIBLE
        reportSuccess.postDelayed({
            reportSuccess.visibility = TextView.GONE
        }, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraExecutor.shutdown()
        locationHelper.stopLocationUpdates()
    }
}