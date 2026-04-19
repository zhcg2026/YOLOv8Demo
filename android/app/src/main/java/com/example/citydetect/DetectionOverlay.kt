package com.example.citydetect

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

data class Detection(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF
)

class DetectionOverlay @JvmOverloads constructor(
    context: android.content.Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detections: List<Detection> = emptyList()
    private val paint = Paint()
    private val textPaint = Paint()

    init {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.isAntiAlias = true

        textPaint.style = Paint.Style.FILL
        textPaint.color = Color.WHITE
        textPaint.textSize = 32f
        textPaint.isAntiAlias = true
    }

    fun updateDetections(detections: List<Detection>) {
        this.detections = detections
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        detections.forEach { detection ->
            // 根据类别选择颜色
            paint.color = when (detection.label) {
                "单体垃圾" -> Color.RED
                "占道经营" -> Color.BLUE
                else -> Color.GREEN
            }

            // 绘制边框
            canvas.drawRect(detection.boundingBox, paint)

            // 绘制标签
            val label = "${detection.label} %.1f%%".format(detection.confidence * 100)
            val textWidth = textPaint.measureText(label)

            // 标签背景
            paint.style = Paint.Style.FILL
            paint.alpha = 180
            canvas.drawRect(
                detection.boundingBox.left,
                detection.boundingBox.top - 40f,
                detection.boundingBox.left + textWidth + 10f,
                detection.boundingBox.top,
                paint
            )
            paint.style = Paint.Style.STROKE
            paint.alpha = 255

            // 标签文字
            canvas.drawText(
                label,
                detection.boundingBox.left + 5f,
                detection.boundingBox.top - 10f,
                textPaint
            )
        }
    }
}