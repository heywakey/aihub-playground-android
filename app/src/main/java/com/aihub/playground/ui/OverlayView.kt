package com.aihub.playground.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.aihub.playground.vision.VisionResult

/**
 * カメラプレビュー(PreviewView, FILL_CENTER)の上に推論結果を描画するオーバーレイ。
 * VisionResult の型ごとに描き分ける。フレーム座標→ビュー座標は FILL_CENTER と同じ
 * center-crop 変換で合わせる。
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var result: VisionResult? = null

    private val boxPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.GREEN }
    private val srcPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.YELLOW }
    private val textPaint = Paint().apply { color = Color.WHITE; textSize = 36f; isAntiAlias = true }
    private val labelTextPaint = Paint().apply { color = Color.WHITE; textSize = 40f; isAntiAlias = true }
    private val textBgPaint = Paint().apply { style = Paint.Style.FILL; color = 0x99000000.toInt() }
    private val maskPaint = Paint().apply { isFilterBitmap = true }
    private val insetBorder = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.WHITE }
    private val chipPaint = Paint().apply { style = Paint.Style.FILL }
    private val srcRectF = RectF()
    private val dstRect = Rect()

    fun setResult(r: VisionResult?) { result = r; invalidate() }
    fun clear() { result = null; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (val r = result) {
            is VisionResult.Detections -> drawDetections(canvas, r)
            is VisionResult.Classification -> drawClassification(canvas, r)
            is VisionResult.Segmentation -> drawFullFrameBitmap(canvas, r.colored).also { drawLegend(canvas, r.legend) }
            is VisionResult.Depth -> drawFullFrameBitmap(canvas, r.colored)
            is VisionResult.SuperRes -> drawSuperRes(canvas, r)
            null -> {}
        }
    }

    // FILL_CENTER の変換係数(frameW,frameH のフレームがビューにどう載るか)
    private data class Xf(val scale: Float, val dx: Float, val dy: Float)

    private fun fillCenter(frameW: Int, frameH: Int): Xf {
        val scale = maxOf(width.toFloat() / frameW, height.toFloat() / frameH)
        return Xf(scale, (width - frameW * scale) / 2f, (height - frameH * scale) / 2f)
    }

    private fun drawDetections(canvas: Canvas, r: VisionResult.Detections) {
        if (r.frameW <= 0 || r.frameH <= 0) return
        val xf = fillCenter(r.frameW, r.frameH)
        for (det in r.items) {
            val box = RectF(
                det.box.left * xf.scale + xf.dx, det.box.top * xf.scale + xf.dy,
                det.box.right * xf.scale + xf.dx, det.box.bottom * xf.scale + xf.dy,
            )
            canvas.drawRect(box, boxPaint)
            val text = "%s %.0f%%".format(det.label, det.score * 100)
            val tw = labelTextPaint.measureText(text)
            canvas.drawRect(box.left, box.top - 48f, box.left + tw + 16f, box.top, textBgPaint)
            canvas.drawText(text, box.left + 8f, box.top - 10f, labelTextPaint)
        }
    }

    private fun drawClassification(canvas: Canvas, r: VisionResult.Classification) {
        var y = 140f
        for ((name, prob) in r.topK) {
            val text = "%s  %.1f%%".format(name, prob * 100)
            val tw = textPaint.measureText(text)
            canvas.drawRect(24f, y - 34f, 24f + tw + 20f, y + 12f, textBgPaint)
            canvas.drawText(text, 34f, y, textPaint)
            y += 58f
        }
    }

    /** フレーム全域に対応する結果 Bitmap を FILL_CENTER で伸長描画。 */
    private fun drawFullFrameBitmap(canvas: Canvas, bmp: android.graphics.Bitmap) {
        // フレーム全体 = ビュー全体(center-crop)にそのまま載せる
        canvas.drawBitmap(bmp, null, Rect(0, 0, width, height), maskPaint)
    }

    private fun drawLegend(canvas: Canvas, legend: List<Pair<String, Int>>) {
        var y = 140f
        for ((name, color) in legend.take(8)) {
            chipPaint.color = color or (0xFF shl 24)
            canvas.drawRect(24f, y - 28f, 60f, y + 4f, chipPaint)
            val tw = textPaint.measureText(name)
            canvas.drawRect(68f, y - 28f, 68f + tw + 16f, y + 4f, textBgPaint)
            canvas.drawText(name, 76f, y, textPaint)
            y += 46f
        }
    }

    private fun drawSuperRes(canvas: Canvas, r: VisionResult.SuperRes) {
        // 元領域の枠をプレビュー上に
        val xf = fillCenter(r.frameW, r.frameH)
        srcRectF.set(
            r.sourceRect.left * xf.scale + xf.dx, r.sourceRect.top * xf.scale + xf.dy,
            r.sourceRect.right * xf.scale + xf.dx, r.sourceRect.bottom * xf.scale + xf.dy,
        )
        canvas.drawRect(srcRectF, srcPaint)
        // 拡大結果を右下にインセット表示
        val side = (minOf(width, height) * 0.5f)
        val margin = 24f
        dstRect.set((width - side - margin).toInt(), (height - side - margin).toInt(),
            (width - margin).toInt(), (height - margin).toInt())
        canvas.drawBitmap(r.upscaled, null, dstRect, maskPaint)
        canvas.drawRect(RectF(dstRect), insetBorder)
        val cap = "super-resolved"
        canvas.drawRect(dstRect.left.toFloat(), dstRect.top - 44f,
            dstRect.left + textPaint.measureText(cap) + 16f, dstRect.top.toFloat(), textBgPaint)
        canvas.drawText(cap, dstRect.left + 8f, dstRect.top - 10f, textPaint)
    }
}
