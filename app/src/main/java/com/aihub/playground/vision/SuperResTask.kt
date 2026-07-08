package com.aihub.playground.vision

import android.graphics.Bitmap
import android.graphics.RectF
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 超解像(XLSR 等)。フレーム中央の入力サイズ分のネイティブ画素を切り出し(縮小せず)、
 * 4x 等に拡大した画像を返す。元領域の枠(sourceRect)も渡してプレビューに重ねる。
 * 出力 upscaled_image[1,S,S,3] uint8(scale 1/255)なので生値がそのまま画素。
 */
class SuperResTask(
    private val engine: LiteRtEngine,
    override val label: String,
) : VisionTask {

    override val backend get() = engine.backend
    private val interp = engine.interpreter
    private val inputSize = engine.inputSize
    private val lut = engine.buildInputLut()

    private val outShape = interp.getOutputTensor(0).shape() // [1,S,S,3]
    private val outH = outShape[1]
    private val outW = outShape[2]

    private val inputBuf: ByteBuffer = engine.newInputBuffer()
    private val inPixels = IntArray(inputSize * inputSize)
    private val outBuf = ByteBuffer.allocateDirect(outH * outW * 3).order(ByteOrder.nativeOrder())
    private val outPixels = IntArray(outH * outW)
    private val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

    override fun run(upright: Bitmap): VisionResult {
        val side = inputSize
        val left = ((upright.width - side) / 2).coerceAtLeast(0)
        val top = ((upright.height - side) / 2).coerceAtLeast(0)
        val cropW = minOf(side, upright.width)
        val cropH = minOf(side, upright.height)
        var patch = Bitmap.createBitmap(upright, left, top, cropW, cropH)
        if (cropW != side || cropH != side) {
            val scaled = Bitmap.createScaledBitmap(patch, side, side, true)
            patch.recycle(); patch = scaled
        }
        engine.fillInput(patch, inputBuf, inPixels, lut)
        // 同じ入力パッチを出力解像度へ単純(バイリニア)拡大して比較対象にする
        val bilinear = Bitmap.createScaledBitmap(patch, outW, outH, true)
        patch.recycle()

        outBuf.rewind()
        interp.run(inputBuf, outBuf)
        outBuf.rewind()

        val q = interp.getOutputTensor(0).quantizationParams()
        val useLut = q.scale > 0f && q.scale != 1f / 255f
        for (i in 0 until outH * outW) {
            val o = i * 3
            val r = deq(outBuf.get(o), q, useLut)
            val g = deq(outBuf.get(o + 1), q, useLut)
            val b = deq(outBuf.get(o + 2), q, useLut)
            outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        outBitmap.setPixels(outPixels, 0, outW, 0, 0, outW, outH)

        val srcRect = RectF(left.toFloat(), top.toFloat(), (left + cropW).toFloat(), (top + cropH).toFloat())
        return VisionResult.SuperRes(outBitmap, bilinear, srcRect, upright.width, upright.height)
    }

    /** real=q*scale∈[0,1] を 0..255 の画素へ。scale が 1/255 相当なら生値。 */
    private fun deq(b: Byte, q: org.tensorflow.lite.Tensor.QuantizationParams, useLut: Boolean): Int {
        val raw = b.toInt() and 0xFF
        if (!useLut) return raw
        val real = (raw - q.zeroPoint) * q.scale
        return Math.round(real * 255f).coerceIn(0, 255)
    }

    override fun close() = engine.close()
}
