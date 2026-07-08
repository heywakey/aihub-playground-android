package com.aihub.playground.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.aihub.playground.model.Detection

/**
 * カメラプレビューの上に検出結果(ボックス+ラベル)を描画するオーバーレイ。
 * setResults() に解析フレームのサイズを渡すと、ビューのサイズに合わせて
 * center-crop(PreviewView の FILL_CENTER と同じ規則)でスケールして描画する。
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var detections: List<Detection> = emptyList()
    private var frameWidth = 0
    private var frameHeight = 0

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.GREEN
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
    }
    private val textBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x99000000.toInt()
    }

    fun setResults(results: List<Detection>, frameWidth: Int, frameHeight: Int) {
        this.detections = results
        this.frameWidth = frameWidth
        this.frameHeight = frameHeight
        invalidate()
    }

    fun clear() {
        detections = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frameWidth <= 0 || frameHeight <= 0) return

        // PreviewView(FILL_CENTER)と同じ center-crop スケーリング
        val scale = maxOf(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
        val dx = (width - frameWidth * scale) / 2f
        val dy = (height - frameHeight * scale) / 2f

        for (det in detections) {
            val r = RectF(
                det.box.left * scale + dx,
                det.box.top * scale + dy,
                det.box.right * scale + dx,
                det.box.bottom * scale + dy,
            )
            canvas.drawRect(r, boxPaint)

            val label = "%s %.0f%%".format(det.label, det.score * 100)
            val textWidth = textPaint.measureText(label)
            val textHeight = textPaint.textSize
            canvas.drawRect(
                r.left,
                r.top - textHeight - 12f,
                r.left + textWidth + 16f,
                r.top,
                textBgPaint,
            )
            canvas.drawText(label, r.left + 8f, r.top - 8f, textPaint)
        }
    }
}
