package com.example.citydetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

class YOLOv8Detector(private val context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private val inputSize = 640
    private val labels: List<String> = getCOCOLabels()
    private var isInitialized = false
    private var initError: String? = null

    init {
        try {
            // 加载 ONNX 模型
            Log.d("YOLOv8", "开始加载模型...")
            val modelFileName = resolveModelFileName()
            val modelFile = copyAssetModelToCache(modelFileName)
            val sessionOptions = OrtSession.SessionOptions()
            session = try {
                // 优先按文件路径加载，避免某些模型在 byte[] 方式下出现 ORT_INVALID_ARGUMENT。
                env.createSession(modelFile.absolutePath, sessionOptions)
            } catch (pathError: Exception) {
                Log.w("YOLOv8", "按路径加载失败，尝试byte[]加载: ${pathError.message}")
                val modelBytes = loadModelFile(modelFileName)
                env.createSession(modelBytes, sessionOptions)
            }
            Log.d("YOLOv8", "模型加载成功: $modelFileName")
            isInitialized = true
            initError = null
        } catch (e: Exception) {
            Log.e("YOLOv8", "模型加载失败: ${e.message}", e)
            isInitialized = false
            val raw = "${e.javaClass.simpleName}: ${e.message}".trim()
            initError = when {
                raw.contains("ORT_INVALID_ARGUMENT", ignoreCase = true) ->
                    "$raw\n建议重导出ONNX：opset=12, imgsz=640, dynamic=False, simplify=True"
                raw.contains("opset", ignoreCase = true) || raw.contains("IR version", ignoreCase = true) ->
                    "$raw\n模型版本与手机端ORT不兼容，请降低opset后重新导出"
                else -> raw
            }
        }
    }

    fun isReady(): Boolean = isInitialized && session != null

    fun getInitError(): String? = initError

    private fun loadModelFile(filename: String): ByteArray {
        return context.assets.open(filename).readBytes()
    }

    private fun copyAssetModelToCache(filename: String): File {
        val outFile = File(context.cacheDir, filename)
        context.assets.open(filename).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }

    private fun resolveModelFileName(): String {
        val preferred = listOf(
            "yolov8n.onnx",
            "best.onnx",
            "model.onnx"
        )
        val assets = context.assets.list("")?.toList().orEmpty()

        val preferredFound = preferred.firstOrNull { assets.contains(it) }
        if (preferredFound != null) return preferredFound

        val anyOnnx = assets.firstOrNull { it.endsWith(".onnx", ignoreCase = true) }
        if (anyOnnx != null) return anyOnnx

        throw IllegalStateException(
            "assets目录未找到onnx模型。请将模型放到 app/src/main/assets/，例如 yolov8n.onnx。当前assets文件: ${assets.joinToString(", ")}"
        )
    }

    private fun getCOCOLabels(): List<String> {
        return listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
            "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote",
            "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book",
            "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
        )
    }

    fun detect(bitmap: Bitmap, confidenceThreshold: Float = 0.5f): List<Detection> {
        if (!isInitialized || session == null) {
            Log.w("YOLOv8", "检测器未初始化")
            return emptyList()
        }

        try {
        // 预处理：letterbox 到 640x640（保持宽高比，与 Ultralytics 训练/导出一致），避免拉伸导致框偏移
        val w0 = bitmap.width
        val h0 = bitmap.height
        val letterScale = min(inputSize / w0.toFloat(), inputSize / h0.toFloat())
        val newW = max(1, (w0 * letterScale).toInt())
        val newH = max(1, (h0 * letterScale).toInt())
        val padX = (inputSize - newW) / 2f
        val padY = (inputSize - newH) / 2f

        val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        val letterboxed = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        Canvas(letterboxed).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(resized, padX, padY, null)
        }
        if (resized != bitmap) {
            resized.recycle()
        }

        val floatBuffer = FloatBuffer.allocate(1 * 3 * inputSize * inputSize)
        val pixels = IntArray(inputSize * inputSize)
        letterboxed.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        letterboxed.recycle()

        // 转换像素值（归一化到 0-1），并按 NCHW 的 CHW 顺序填充。
        val imageArea = inputSize * inputSize
        for (i in 0 until imageArea) {
            val pixel = pixels[i]
            floatBuffer.put(i, ((pixel shr 16) and 0xFF) / 255.0f)                 // R channel
            floatBuffer.put(i + imageArea, ((pixel shr 8) and 0xFF) / 255.0f)      // G channel
            floatBuffer.put(i + imageArea * 2, (pixel and 0xFF) / 255.0f)          // B channel
        }
        floatBuffer.rewind()

        // 创建输入张量
        val inputTensor = OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()))

        // 运行推理
        val inputName = session!!.inputNames.iterator().next()
        val results = session!!.run(mapOf(inputName to inputTensor))

        // 解析输出：YOLOv8 输出形状 [1, 84, 8400]
        val outputValue = results[0].value
        val outputTensor = when (outputValue) {
            is Array<*> -> {
                if (outputValue.isNotEmpty() && outputValue[0] is Array<*>) {
                    @Suppress("UNCHECKED_CAST")
                    (outputValue[0] as Array<FloatArray>)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    (outputValue as Array<FloatArray>)
                }
            }
            else -> {
                Log.e("YOLOv8", "不支持的输出类型: ${outputValue?.javaClass?.name}")
                return emptyList()
            }
        }
        val detections = parseOutput(outputTensor, letterScale, padX, padY, w0, h0, confidenceThreshold)

        results.close()
        inputTensor.close()

        return applyNms(detections)
        } catch (e: Exception) {
            Log.e("YOLOv8", "检测失败: ${e.message}", e)
            return emptyList()
        }
    }

    private fun parseOutput(
        output: Array<FloatArray>,
        letterScale: Float,
        padX: Float,
        padY: Float,
        imageWidth: Int,
        imageHeight: Int,
        confidenceThreshold: Float
    ): List<Detection> {
        val detections = mutableListOf<Detection>()

        // YOLOv8 输出: [84, 8400] -> 8400个检测框，每个有84个值 (4坐标 + 80类别)
        val numDetections = output[0].size // 8400
        val numClasses = output.size - 4   // 80

        for (i in 0 until numDetections) {
            // 获取坐标
            val cx = output[0][i]
            val cy = output[1][i]
            val w = output[2][i]
            val h = output[3][i]

            // 找最大置信度类别
            var maxConf = 0f
            var maxClass = 0
            for (j in 4 until output.size) {
                if (output[j][i] > maxConf) {
                    maxConf = output[j][i]
                    maxClass = j - 4
                }
            }

            if (maxConf > confidenceThreshold) {
                // 网络输出在 640×640 letterbox 坐标系；先去掉 padding，再映射回原始 Bitmap 像素
                val left640 = cx - w / 2f
                val top640 = cy - h / 2f
                val right640 = cx + w / 2f
                val bottom640 = cy + h / 2f

                val left = ((left640 - padX) / letterScale).coerceIn(0f, imageWidth.toFloat())
                val top = ((top640 - padY) / letterScale).coerceIn(0f, imageHeight.toFloat())
                val right = ((right640 - padX) / letterScale).coerceIn(0f, imageWidth.toFloat())
                val bottom = ((bottom640 - padY) / letterScale).coerceIn(0f, imageHeight.toFloat())

                // Demo阶段：person映射为占道经营，其他映射为单体垃圾
                val label = if (maxClass == 0) "占道经营" else "单体垃圾"

                detections.add(Detection(
                    label = label,
                    confidence = maxConf,
                    boundingBox = RectF(left, top, right, bottom)
                ))
            }
        }

        return detections
    }

    private fun applyNms(
        detections: List<Detection>,
        iouThreshold: Float = 0.45f
    ): List<Detection> {
        if (detections.isEmpty()) return detections

        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {
            val current = sorted.removeAt(0)
            selected.add(current)
            sorted.removeAll { candidate ->
                candidate.label == current.label && iou(current.boundingBox, candidate.boundingBox) > iouThreshold
            }
        }

        return selected
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        val interWidth = maxOf(0f, interRight - interLeft)
        val interHeight = maxOf(0f, interBottom - interTop)
        val interArea = interWidth * interHeight
        if (interArea <= 0f) return 0f

        val areaA = maxOf(0f, a.width()) * maxOf(0f, a.height())
        val areaB = maxOf(0f, b.width()) * maxOf(0f, b.height())
        val union = areaA + areaB - interArea
        if (union <= 0f) return 0f

        return interArea / union
    }
}