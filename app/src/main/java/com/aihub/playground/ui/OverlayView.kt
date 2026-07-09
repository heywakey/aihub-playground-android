package com.aihub.playground.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.aihub.playground.vision.VisionResult
import kotlin.math.max
import kotlin.math.min

/**
 * カメラプレビュー(PreviewView, FILL_CENTER)の上に推論結果を描画するオーバーレイ。
 * VisionResult の型ごとに描き分ける。フレーム座標→ビュー座標は FILL_CENTER と同じ
 * center-crop 変換で合わせる。フロントカメラ時は [mirror] で左右反転を合わせる。
 * 超解像/セグ/深度では [roiEnabled] で処理領域(ROI)をドラッグ・ピンチ操作できる。
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var result: VisionResult? = null

    /** フロントカメラ時 true。空間系の描画を左右反転してプレビューに合わせる。 */
    var mirror: Boolean = false
        set(value) { field = value; invalidate() }

    /** ROI(領域選択)を有効化。超解像/セグ/深度で使う。 */
    var roiEnabled: Boolean = false
        set(value) {
            field = value
            if (value) ensureRoi()
            invalidate()
        }

    private val boxPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.GREEN }
    private val textPaint = Paint().apply { color = Color.WHITE; textSize = 36f; isAntiAlias = true }
    private val labelTextPaint = Paint().apply { color = Color.WHITE; textSize = 40f; isAntiAlias = true }
    private val textBgPaint = Paint().apply { style = Paint.Style.FILL; color = 0x99000000.toInt() }
    private val panelPaint = Paint().apply { style = Paint.Style.FILL; color = 0xB2000000.toInt() }
    private val barBgPaint = Paint().apply { style = Paint.Style.FILL; color = 0x33FFFFFF }
    private val barPaint = Paint().apply { style = Paint.Style.FILL; color = 0xFF4F8CFF.toInt() }
    private val barTopPaint = Paint().apply { style = Paint.Style.FILL; color = 0xFF2DD4BF.toInt() }
    private val maskPaint = Paint().apply { isFilterBitmap = true }
    private val insetBorder = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.WHITE }
    private val chipPaint = Paint().apply { style = Paint.Style.FILL }
    private val roiPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFFFFFFFF.toInt(); isAntiAlias = true
    }
    private val roiCornerPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 8f; color = 0xFF2DD4BF.toInt(); isAntiAlias = true
    }
    private val dstRect = Rect()

    // ---- ROI 状態(ビュー座標の正方形) ----
    private val roiRect = RectF()
    private var roiReady = false
    @Volatile private var roiSnap: RectF? = null

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                resizeRoi(d.scaleFactor); return true
            }
        })
    private var lastX = 0f
    private var lastY = 0f

    fun setResult(r: VisionResult?) { result = r; invalidate() }
    fun clear() { result = null; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (roiEnabled) ensureRoi()
    }

    private fun ensureRoi() {
        if (roiReady || width == 0 || height == 0) return
        val side = 0.6f * min(width, height)
        val cx = width / 2f; val cy = height / 2f
        roiRect.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        roiReady = true
        roiSnap = RectF(roiRect)
    }

    private fun resizeRoi(factor: Float) {
        val cx = roiRect.centerX(); val cy = roiRect.centerY()
        val minSide = 0.18f * min(width, height)
        val maxSide = min(width, height).toFloat()
        val side = (roiRect.width() * factor).coerceIn(minSide, maxSide)
        roiRect.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        clampRoi(); roiSnap = RectF(roiRect); invalidate()
    }

    private fun translateRoi(dx: Float, dy: Float) {
        roiRect.offset(dx, dy); clampRoi(); roiSnap = RectF(roiRect); invalidate()
    }

    private fun clampRoi() {
        val w = roiRect.width(); val h = roiRect.height()
        if (roiRect.left < 0) roiRect.set(0f, roiRect.top, w, roiRect.top + h)
        if (roiRect.top < 0) roiRect.set(roiRect.left, 0f, roiRect.left + w, h)
        if (roiRect.right > width) roiRect.set(width - w, roiRect.top, width.toFloat(), roiRect.top + h)
        if (roiRect.bottom > height) roiRect.set(roiRect.left, height - h, roiRect.left + w, height.toFloat())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!roiEnabled) return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
            MotionEvent.ACTION_MOVE ->
                if (!scaleDetector.isInProgress) {
                    translateRoi(event.x - lastX, event.y - lastY); lastX = event.x; lastY = event.y
                }
        }
        return true
    }

    /** ROI をフレーム座標へ逆変換して返す(タスクが切り出しに使う)。mirror も考慮。 */
    fun frameRoi(frameW: Int, frameH: Int): RectF? {
        val r = roiSnap ?: return null
        if (frameW <= 0 || frameH <= 0 || width == 0 || height == 0) return null
        val scale = max(width.toFloat() / frameW, height.toFloat() / frameH)
        val dx = (width - frameW * scale) / 2f
        val dy = (height - frameH * scale) / 2f
        var vl = r.left; var vr = r.right
        if (mirror) { val nl = width - r.right; val nr = width - r.left; vl = nl; vr = nr }
        val fl = ((vl - dx) / scale).coerceIn(0f, frameW.toFloat())
        val fr = ((vr - dx) / scale).coerceIn(0f, frameW.toFloat())
        val ft = ((r.top - dy) / scale).coerceIn(0f, frameH.toFloat())
        val fb = ((r.bottom - dy) / scale).coerceIn(0f, frameH.toFloat())
        return RectF(min(fl, fr), min(ft, fb), max(fl, fr), max(ft, fb))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (val r = result) {
            is VisionResult.Detections -> drawDetections(canvas, r)
            is VisionResult.Classification -> drawClassification(canvas, r)
            is VisionResult.Segmentation -> { drawMask(canvas, r.colored); drawLegend(canvas, r.legend) }
            is VisionResult.Depth -> drawMask(canvas, r.colored)
            is VisionResult.SuperRes -> drawSuperRes(canvas, r)
            null -> {}
        }
        if (roiEnabled && roiReady) drawRoi(canvas)
    }

    // FILL_CENTER の変換係数(frameW,frameH のフレームがビューにどう載るか)
    private data class Xf(val scale: Float, val dx: Float, val dy: Float)

    private fun fillCenter(frameW: Int, frameH: Int): Xf {
        val scale = max(width.toFloat() / frameW, height.toFloat() / frameH)
        return Xf(scale, (width - frameW * scale) / 2f, (height - frameH * scale) / 2f)
    }

    /** frame x をビュー x へ(mirror 対応)。 */
    private fun mapX(fx: Float, xf: Xf): Float {
        val vx = fx * xf.scale + xf.dx
        return if (mirror) width - vx else vx
    }

    private fun drawDetections(canvas: Canvas, r: VisionResult.Detections) {
        if (r.frameW <= 0 || r.frameH <= 0) return
        val xf = fillCenter(r.frameW, r.frameH)
        for (det in r.items) {
            val x1 = mapX(det.box.left, xf); val x2 = mapX(det.box.right, xf)
            val box = RectF(
                min(x1, x2), det.box.top * xf.scale + xf.dy,
                max(x1, x2), det.box.bottom * xf.scale + xf.dy,
            )
            canvas.drawRect(box, boxPaint)
            val text = "%s %.0f%%".format(det.label, det.score * 100)
            val tw = labelTextPaint.measureText(text)
            canvas.drawRect(box.left, box.top - 48f, box.left + tw + 16f, box.top, textBgPaint)
            canvas.drawText(text, box.left + 8f, box.top - 10f, labelTextPaint)
        }
    }

    /** 分類: 角丸パネル + 確信度バーで上位K を表示。 */
    private fun drawClassification(canvas: Canvas, r: VisionResult.Classification) {
        if (r.topK.isEmpty()) return
        val left = 24f; val top = 96f
        val rowH = 64f; val panelW = min(width * 0.5f, 520f)
        val panelH = rowH * r.topK.size + 24f
        canvas.drawRoundRect(left, top, left + panelW, top + panelH, 24f, 24f, panelPaint)
        var y = top + 20f
        r.topK.forEachIndexed { i, (name, prob) ->
            val rowTop = y
            // ラベル
            canvas.drawText(name, left + 20f, rowTop + 26f, textPaint)
            // 確信度バー
            val barL = left + 20f; val barR = left + panelW - 20f
            val barTop = rowTop + 34f; val barBot = rowTop + 48f
            canvas.drawRoundRect(barL, barTop, barR, barBot, 8f, 8f, barBgPaint)
            val fill = barL + (barR - barL) * prob.coerceIn(0f, 1f)
            canvas.drawRoundRect(barL, barTop, fill, barBot, 8f, 8f, if (i == 0) barTopPaint else barPaint)
            // パーセント
            val pct = "%.0f%%".format(prob * 100)
            canvas.drawText(pct, barR - textPaint.measureText(pct), rowTop + 26f, textPaint)
            y += rowH
        }
    }

    /** セグ/深度の色付きマスク。ROI 有効ならその矩形内、無効ならフレーム全域に描く。mirror 対応。 */
    private fun drawMask(canvas: Canvas, bmp: android.graphics.Bitmap) {
        val target = if (roiEnabled && roiReady) roiRect else RectF(0f, 0f, width.toFloat(), height.toFloat())
        dstRect.set(target.left.toInt(), target.top.toInt(), target.right.toInt(), target.bottom.toInt())
        if (mirror) {
            canvas.save()
            canvas.scale(-1f, 1f, target.centerX(), target.centerY())
            canvas.drawBitmap(bmp, null, dstRect, maskPaint)
            canvas.restore()
        } else {
            canvas.drawBitmap(bmp, null, dstRect, maskPaint)
        }
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
        // 下部に「バイリニア | SR」の左右比較インセット(同一クロップ・同一表示サイズ)
        val margin = 20f
        val gap = 8f
        val paneW = (width - margin * 2 - gap) / 2f
        val paneH = paneW // 正方
        val top = height - paneH - margin
        drawPane(canvas, r.bilinear, margin, top, paneW, paneH, "bilinear ×4")
        drawPane(canvas, r.upscaled, margin + paneW + gap, top, paneW, paneH, "SR ×4")
    }

    private fun drawPane(canvas: Canvas, bmp: android.graphics.Bitmap,
                         x: Float, y: Float, w: Float, h: Float, caption: String) {
        dstRect.set(x.toInt(), y.toInt(), (x + w).toInt(), (y + h).toInt())
        canvas.drawBitmap(bmp, null, dstRect, maskPaint)
        canvas.drawRect(RectF(dstRect), insetBorder)
        val tw = textPaint.measureText(caption)
        canvas.drawRect(x, y, x + tw + 16f, y + 44f, textBgPaint)
        canvas.drawText(caption, x + 8f, y + 32f, textPaint)
    }

    /** ROI の枠(コーナーブラケット付き)。 */
    private fun drawRoi(canvas: Canvas) {
        canvas.drawRect(roiRect, roiPaint)
        val c = min(roiRect.width(), roiRect.height()) * 0.18f
        val l = roiRect.left; val t = roiRect.top; val rr = roiRect.right; val b = roiRect.bottom
        // 四隅
        canvas.drawLine(l, t, l + c, t, roiCornerPaint); canvas.drawLine(l, t, l, t + c, roiCornerPaint)
        canvas.drawLine(rr, t, rr - c, t, roiCornerPaint); canvas.drawLine(rr, t, rr, t + c, roiCornerPaint)
        canvas.drawLine(l, b, l + c, b, roiCornerPaint); canvas.drawLine(l, b, l, b - c, roiCornerPaint)
        canvas.drawLine(rr, b, rr - c, b, roiCornerPaint); canvas.drawLine(rr, b, rr, b - c, roiCornerPaint)
    }
}
