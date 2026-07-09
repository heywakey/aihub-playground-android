package com.aihub.playground.vision

import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 単眼深度推定(MiDaS)。フレーム全体を入力サイズへ伸長 → depth[1,H,W,1] uint8。
 * 相対深度なのでフレーム内 min-max で正規化し、カラーマップを当てて可視化する。
 */
class DepthTask(
    private val engine: LiteRtEngine,
    override val label: String,
) : VisionTask, RoiAware {

    @Volatile override var roi: android.graphics.RectF? = null

    override val backend get() = engine.backend
    private val interp = engine.interpreter
    private val inputSize = engine.inputSize
    private val lut = engine.buildInputLut()

    private val outShape = interp.getOutputTensor(0).shape() // [1,H,W,1] or [1,H,W]
    private val outH = outShape[1]
    private val outW = outShape[2]
    private val outIsFloat = interp.getOutputTensor(0).dataType() == DataType.FLOAT32

    private val inputBuf: ByteBuffer = engine.newInputBuffer()
    private val inPixels = IntArray(inputSize * inputSize)
    private val depthOut = ByteBuffer.allocateDirect(outH * outW * if (outIsFloat) 4 else 1)
        .order(ByteOrder.nativeOrder())
    private val depthVals = FloatArray(outH * outW)
    private val colorPixels = IntArray(outH * outW)
    private val colored = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    private val cmap = turboColormap()

    override fun run(upright: Bitmap): VisionResult {
        // ROI を正方クロップ(全画面伸長をやめアスペクト維持)。null なら中央最大正方形。
        val square = ImageUtils.cropRoiSquare(upright, roi, inputSize)
        engine.fillInput(square, inputBuf, inPixels, lut)
        square.recycle()

        depthOut.rewind()
        interp.run(inputBuf, depthOut)
        depthOut.rewind()

        // 相対深度なので dtype に依らずフレーム内 min-max 正規化してカラーマップ
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (i in 0 until outH * outW) {
            val v = if (outIsFloat) depthOut.getFloat(i * 4) else (depthOut.get(i).toInt() and 0xFF).toFloat()
            depthVals[i] = v
            if (v < mn) mn = v; if (v > mx) mx = v
        }
        val range = (mx - mn).coerceAtLeast(1e-6f)
        for (i in 0 until outH * outW) {
            val n = ((depthVals[i] - mn) / range * 255f).toInt().coerceIn(0, 255)
            colorPixels[i] = cmap[n]
        }
        colored.setPixels(colorPixels, 0, outW, 0, 0, outW, outH)
        return VisionResult.Depth(colored)
    }

    override fun close() = engine.close()

    /** Turbo 風カラーマップ(近=暖色)。数点のアンカーを線形補間して 256 段。 */
    private fun turboColormap(): IntArray {
        val anchors = arrayOf(
            intArrayOf(48, 18, 59), intArrayOf(33, 115, 189), intArrayOf(28, 191, 147),
            intArrayOf(160, 220, 57), intArrayOf(251, 180, 30), intArrayOf(220, 60, 30),
            intArrayOf(122, 4, 3),
        )
        return IntArray(256) { i ->
            val t = i / 255f * (anchors.size - 1)
            val lo = t.toInt().coerceIn(0, anchors.size - 1)
            val hi = (lo + 1).coerceAtMost(anchors.size - 1)
            val f = t - lo
            val r = (anchors[lo][0] + (anchors[hi][0] - anchors[lo][0]) * f).toInt()
            val g = (anchors[lo][1] + (anchors[hi][1] - anchors[lo][1]) * f).toInt()
            val b = (anchors[lo][2] + (anchors[hi][2] - anchors[lo][2]) * f).toInt()
            Color.argb(255, r, g, b)
        }
    }
}
