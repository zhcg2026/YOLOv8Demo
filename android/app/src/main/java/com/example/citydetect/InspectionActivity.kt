package com.example.citydetect

import android.Manifest
import android.content.Intent
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
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.atan2
import kotlin.math.sqrt

class InspectionActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: DetectionOverlay
    private lateinit var statusText: TextView
    private lateinit var locationText: TextView
    private lateinit var speedText: TextView
    private lateinit var speedWarning: TextView
    private lateinit var reportSuccess: TextView
    private lateinit var stopBtn: Button

    private lateinit var cameraExecutor: ExecutorService
    private var detector: YOLOv8Detector? = null
    private lateinit var locationHelper: LocationHelper
    private lateinit var reportApi: ReportApi
    private lateinit var geocodingHelper: GeocodingHelper
    private lateinit var configManager: ConfigManager
    private lateinit var trackRecorder: TrackRecorder
    private lateinit var videoRecorder: VideoRecorder

    private var lastReportTime = 0L
    private val reportInterval = 5000L
    private val reportMutex = Mutex()
    @Volatile private var reportInProgress = false
    private var lastSuccessfulLatLng: Pair<Double, Double>? = null
    private var currentSpeedKmh = 0.0f

    // 用于计算速度
    private var prevSpeedLatLng: Pair<Double, Double>? = null
    private var prevSpeedSampleTimeMs = 0L
    private val speedWindow = ArrayDeque<Float>()
    private val speedWindowSize = 5
    private val maxValidSpeedKmh = 60.0f
    private val minSpeedCalcDistanceMeters = 0.8
    private val maxAcceptableAccuracyMeters = 120.0f
    private var lastSmoothedSpeedKmh = 0f
    private val speedEmaAlpha = 0.35f
    private val maxSpeedDeltaPerUpdateKmh = 2.5f
    private val maxStaleLocationAgeMs = 10_000L
    private val invalidSpeedGracePeriodMs = 12_000L
    private val maxInvalidSpeedSamplesInGrace = 8
    private val invalidSpeedRecoveryTriggerSamples = 6
    private val recoveryCooldownMs = 20_000L
    private var lastRecoveryAttemptTimeMs = 0L
    private var lastReliableSpeedKmh = 0f
    private var lastReliableSpeedTimeMs = 0L
    private var consecutiveInvalidSpeedSamples = 0
    private var debugOverlayEnabled = false

    // 去重记录
    private data class ReportedItem(
        val latitude: Double,
        val longitude: Double,
        val problemType: String,
        val reportTime: Long
    )
    private val reportedItems = mutableListOf<ReportedItem>()
    private val dedupDistanceMeters = 20.0
    private val dedupTimeMinutes = 5L

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
            trackRecorder.startRecording()
        } else {
            Toast.makeText(this, "需要摄像头和定位权限", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspection)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 检查登录状态
        if (!ConfigManager.getInstance(this).isLoggedIn()) {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        // 初始化视图
        previewView = findViewById(R.id.previewView)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        overlayView = findViewById(R.id.overlayView)
        statusText = findViewById(R.id.statusText)
        locationText = findViewById(R.id.locationText)
        speedText = findViewById(R.id.speedText)
        speedWarning = findViewById(R.id.speedWarning)
        reportSuccess = findViewById(R.id.reportSuccess)
        stopBtn = findViewById(R.id.stopBtn)
        speedText.setOnLongClickListener {
            debugOverlayEnabled = !debugOverlayEnabled
            Toast.makeText(
                this,
                if (debugOverlayEnabled) "调试信息已开启" else "调试信息已关闭",
                Toast.LENGTH_SHORT
            ).show()
            true
        }

        // 初始化配置
        configManager = ConfigManager.getInstance(this)
        geocodingHelper = GeocodingHelper(this)

        // 初始化组件
        cameraExecutor = Executors.newSingleThreadExecutor()
        locationHelper = LocationHelper(this) { lat, lng, speed ->
            runOnUiThread {
                val latestLocation = locationHelper.getCurrentLocation()
                val provider = latestLocation?.provider ?: "unknown"
                val baseLocationText = "位置: %.4f, %.4f".format(lat, lng)
                val accuracy = latestLocation?.accuracy ?: Float.MAX_VALUE
                val locationAgeMs = latestLocation?.let { now ->
                    (System.currentTimeMillis() - now.time).coerceAtLeast(0L)
                } ?: Long.MAX_VALUE
                // 优先使用设备原生GPS速度字段（回调参数speed），避免hasSpeed判定过严导致长期显示0
                val gpsSpeedKmh = if (accuracy <= maxAcceptableAccuracyMeters) {
                    (speed * 3.6f).coerceAtLeast(0f)
                } else {
                    0f
                }

                val now = System.currentTimeMillis()
                var calculatedSpeedKmh = 0.0f
                if (prevSpeedLatLng != null && prevSpeedSampleTimeMs > 0L) {
                    val timeDeltaMs = now - prevSpeedSampleTimeMs
                    if (timeDeltaMs in 800L..30000L) {
                        val (prevLat, prevLng) = prevSpeedLatLng!!
                        val distMeters = distanceMeters(prevLat, prevLng, lat, lng)
                        if (distMeters >= minSpeedCalcDistanceMeters) {
                            val timeDeltaSec = timeDeltaMs / 1000.0f
                            calculatedSpeedKmh = (distMeters / timeDeltaSec * 3.6f).toFloat()
                        }
                    }
                }
                prevSpeedLatLng = lat to lng
                prevSpeedSampleTimeMs = now

                val speedCandidate = when {
                    gpsSpeedKmh in 0.5f..maxValidSpeedKmh -> gpsSpeedKmh
                    calculatedSpeedKmh in 0.5f..maxValidSpeedKmh -> calculatedSpeedKmh
                    else -> 0f
                }
                val effectiveSpeedCandidate = adaptInvalidSpeedSample(
                    speedCandidate = speedCandidate,
                    locationAgeMs = locationAgeMs,
                    nowMs = now
                )
                currentSpeedKmh = smoothSpeed(effectiveSpeedCandidate)

                speedText.text = "速度: %.1f km/h".format(currentSpeedKmh)

                // 速度警告
                if (currentSpeedKmh > 15.0f) {
                    speedWarning.visibility = TextView.VISIBLE
                    speedWarning.text = "速度过快！请保持≤15km/h"
                    speedWarning.setTextColor(Color.RED)
                } else {
                    speedWarning.visibility = TextView.GONE
                }

                val trackAccuracy = latestLocation?.accuracy
                val trackRecordStatus = trackRecorder.recordPoint(lat, lng, trackAccuracy)
                if (debugOverlayEnabled) {
                    val accuracyText = if (trackAccuracy == null) "N/A" else "%.1fm".format(trackAccuracy)
                    locationText.text = "$baseLocationText\n调试: provider=$provider, acc=$accuracyText, track=$trackRecordStatus"
                } else {
                    locationText.text = baseLocationText
                }

                Log.d(
                    "InspectionActivity",
                    "速度: GPS=$gpsSpeedKmh, 计算=$calculatedSpeedKmh, 精度=$accuracy, 最终=$currentSpeedKmh km/h"
                )
            }
            lastSuccessfulLatLng = lat to lng
        }
        reportApi = ReportApi(configManager)
        trackRecorder = TrackRecorder(this, configManager)
        videoRecorder = VideoRecorder(this)

        // 结束巡查按钮
        stopBtn.setOnClickListener {
            finishInspection()
        }

        // 延迟初始化检测器
        CoroutineScope(Dispatchers.IO).launch {
            try {
                detector = YOLOv8Detector(this@InspectionActivity)
                val ready = detector?.isReady() == true
                Log.d("InspectionActivity", "检测器初始化完成，ready=$ready")
                withContext(Dispatchers.Main) {
                    statusText.text = if (ready) "正在检测..." else "模型加载失败"
                }

                // 从服务器获取阈值
                if (ready) {
                    val threshold = reportApi.getThreshold()
                    if (threshold != null) {
                        configManager.reportConfidenceThreshold = threshold
                        Log.d("InspectionActivity", "服务器阈值已应用: $threshold")
                    }
                }
            } catch (e: Exception) {
                Log.e("InspectionActivity", "检测器初始化失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    statusText.text = "模型加载失败"
                }
            }
        }

        // 检查权限
        if (checkPermissions()) {
            startCamera()
            locationHelper.startLocationUpdates()
            trackRecorder.startRecording()
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

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

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
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                // 记录帧到视频缓冲区
                videoRecorder.recordFrame(bitmap)

                val currentDetector = detector
                if (currentDetector == null || !currentDetector.isReady()) {
                    val errorMsg = currentDetector?.getInitError() ?: "检测器初始化中"
                    runOnUiThread {
                        statusText.text = "模型未就绪\n$errorMsg"
                    }
                    return
                }

                val displayThreshold = configManager.displayConfidenceThreshold
                val detections = currentDetector.detect(bitmap, displayThreshold)

                runOnUiThread {
                    val forOverlay = mapDetectionsBitmapToOverlay(
                        detections,
                        bitmap.width,
                        bitmap.height
                    )
                    overlayView.updateDetections(forOverlay)

                    val detectionInfo = if (detections.isNotEmpty()) {
                        "检测到 ${detections.size} 个目标"
                    } else {
                        "正在检测..."
                    }
                    statusText.text = detectionInfo
                }

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

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
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

    private fun isDuplicateReport(latitude: Double, longitude: Double, problemType: String): Boolean {
        val now = System.currentTimeMillis()
        reportedItems.removeAll { now - it.reportTime > dedupTimeMinutes * 60 * 1000 }
        return reportedItems.any { item ->
            val dist = distanceMeters(latitude, longitude, item.latitude, item.longitude)
            dist < dedupDistanceMeters && item.problemType == problemType
        }
    }

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
                // 获取位置
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
                    Log.w("Report", "定位重试第${retryCount}次")
                }

                val (latitude, longitude, locationSource) = if (location != null) {
                    Triple(location.latitude, location.longitude, "GPS")
                } else if (lastSuccessfulLatLng != null) {
                    Triple(lastSuccessfulLatLng!!.first, lastSuccessfulLatLng!!.second, "LAST_KNOWN")
                } else {
                    Triple(0.0, 0.0, "FALLBACK_ZERO")
                }

                Log.w("Report", "上报位置来源: $locationSource, lat=$latitude, lng=$longitude")

                val problemType = detections.first().label
                if (isDuplicateReport(latitude, longitude, problemType)) {
                    Log.d("Report", "去重：跳过重复上报")
                    reportInProgress = false
                    return@launch
                }

                val address = if (locationSource == "FALLBACK_ZERO") {
                    "定位未就绪(0,0)"
                } else {
                    withTimeoutOrNull(4500L) {
                        geocodingHelper.getAddress(latitude, longitude)
                    } ?: String.format("纬度%.6f, 经度%.6f", latitude, longitude)
                }

                lastReportTime = System.currentTimeMillis()
                val annotated = drawDetectionsOnBitmap(bitmap, detections)

                // 录制5秒视频
                val videoBase64 = videoRecorder.captureAndFinish(annotated)

                val result = reportApi.report(
                    image = annotated,
                    latitude = latitude,
                    longitude = longitude,
                    address = address,
                    problemType = detections.first().label,
                    confidence = detections.first().confidence,
                    videoBase64 = videoBase64
                )

                if (annotated != bitmap) {
                    annotated.recycle()
                }

                withContext(Dispatchers.Main) {
                    if (result) {
                        showReportSuccess()
                        recordReport(latitude, longitude, problemType)
                        Log.d("Report", "上报成功")
                    } else {
                        Toast.makeText(this@InspectionActivity, "上报失败，请检查服务器连接", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("Report", "上报失败", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@InspectionActivity, "上报异常: ${e.message}", Toast.LENGTH_SHORT).show()
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

    private fun showReportSuccess() {
        reportSuccess.visibility = TextView.VISIBLE
        reportSuccess.postDelayed({
            reportSuccess.visibility = TextView.GONE
        }, 2000)
    }

    private fun finishInspection() {
        // 停止轨迹记录并上传
        trackRecorder.stopAndUpload()

        Toast.makeText(this, "巡查已结束", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, MainMenuActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun smoothSpeed(rawSpeed: Float): Float {
        speedWindow.addLast(rawSpeed)
        while (speedWindow.size > speedWindowSize) {
            speedWindow.removeFirst()
        }
        if (speedWindow.isEmpty()) {
            return 0f
        }

        // 先做中值滤波，抑制GPS瞬时毛刺
        val nonZero = speedWindow.filter { it > 0.1f }.sorted()
        val median = if (nonZero.isNotEmpty()) {
            nonZero[nonZero.size / 2]
        } else {
            0f
        }

        // 再做EMA平滑，让速度变化更稳定
        val ema = if (lastSmoothedSpeedKmh <= 0.1f) {
            median
        } else {
            (lastSmoothedSpeedKmh * (1f - speedEmaAlpha)) + (median * speedEmaAlpha)
        }

        // 最后限制单次跳变，避免 10->4 这种肉眼抖动
        val delta = ema - lastSmoothedSpeedKmh
        val limited = if (abs(delta) > maxSpeedDeltaPerUpdateKmh) {
            lastSmoothedSpeedKmh + (if (delta > 0) 1 else -1) * maxSpeedDeltaPerUpdateKmh
        } else {
            ema
        }
        lastSmoothedSpeedKmh = limited.coerceIn(0f, maxValidSpeedKmh)
        return lastSmoothedSpeedKmh
    }

    private fun adaptInvalidSpeedSample(
        speedCandidate: Float,
        locationAgeMs: Long,
        nowMs: Long
    ): Float {
        val isFreshLocation = locationAgeMs <= maxStaleLocationAgeMs
        if (speedCandidate > 0.5f && isFreshLocation) {
            lastReliableSpeedKmh = speedCandidate
            lastReliableSpeedTimeMs = nowMs
            consecutiveInvalidSpeedSamples = 0
            return speedCandidate
        }

        consecutiveInvalidSpeedSamples += 1
        val withinGrace = nowMs - lastReliableSpeedTimeMs <= invalidSpeedGracePeriodMs
        val canFallback =
            withinGrace &&
                lastReliableSpeedKmh > 0.5f &&
                consecutiveInvalidSpeedSamples <= maxInvalidSpeedSamplesInGrace

        tryRecoverLocationStreamIfNeeded(
            nowMs = nowMs,
            locationAgeMs = locationAgeMs,
            canFallback = canFallback
        )

        return if (canFallback) {
            // 定位短时抖动或速度字段暂失时，短暂沿用最近可靠速度，避免显示值持续下坠到0
            (lastReliableSpeedKmh * 0.98f).coerceAtLeast(0.5f)
        } else {
            0f
        }
    }

    private fun tryRecoverLocationStreamIfNeeded(
        nowMs: Long,
        locationAgeMs: Long,
        canFallback: Boolean
    ) {
        if (consecutiveInvalidSpeedSamples < invalidSpeedRecoveryTriggerSamples) {
            return
        }
        if (canFallback) {
            return
        }
        if (nowMs - lastRecoveryAttemptTimeMs < recoveryCooldownMs) {
            return
        }

        lastRecoveryAttemptTimeMs = nowMs
        val reason = "invalid_samples=$consecutiveInvalidSpeedSamples, location_age_ms=$locationAgeMs"
        Log.w("InspectionActivity", "检测到定位速度链路异常，触发定位重启: $reason")
        locationHelper.restartLocationUpdates(reason)
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraExecutor.shutdown()
        locationHelper.stopLocationUpdates()
        trackRecorder.stop()
        videoRecorder.stop()
    }
}