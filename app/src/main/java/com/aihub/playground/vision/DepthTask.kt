package com.aihub.playground.vision

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 単眼深度推定(MiDaS)。フレーム全体を入力サイズへ伸長 → depth[1,H,W,1] uint8。
 * 相対深度なのでフレーム内 min-max で正規化し、カラーマップを当てて可視化する。
 */
class DepthTask(
    private val engine: LiteRtEngine,
    override val label: String,
) : VisionTask {

    override val backend get() = engine.backend
    private val interp = engine.interpreter
    private val inputSize = engine.inputSize
    private val lut = engine.buildInputLut()

    private val outShape = interp.getOutputTensor(0).shape() // [1,H,W,1] or [1,H,W]
    private val outH = outShape[1]
    private val outW = outShape[2]

    private val inputBuf: ByteBuffer = engine.newInputBuffer()
    private val inPixels = IntArray(inputSize * inputSize)
    private val depthOut = ByteBuffer.allocateDirect(outH * outW).order(ByteOrder.nativeOrder())
    private val colorPixels = IntArray(outH * outW)
    private val colored = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    private val cmap = turboColormap()

    override fun run(upright: Bitmap): VisionResult {
        val square = ImageUtils.resizeStretch(upright, inputSize)
        engine.fillInput(square, inputBuf, inPixels, lut)
        if (square != upright) square.recycle()

        depthOut.rewind()
        interp.run(inputBuf, depthOut)
        depthOut.rewind()

        var mn = 255; var mx = 0
        for (i in 0 until outH * outW) {
            val v = depthOut.get(i).toInt() and 0xFF
            if (v < mn) mn = v; if (v > mx) mx = v
        }
        val range = (mx - mn).coerceAtLeast(1)
        for (i in 0 until outH * outW) {
            val v = depthOut.get(i).toInt() and 0xFF
            colorPixels[i] = cmap[((v - mn) * 255 / range).coerceIn(0, 255)]
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
