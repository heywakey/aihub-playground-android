package com.aihub.playground.vision

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * セマンティックセグメンテーション(DeepLabV3+ / VOC 21クラス)。
 * フレーム全体を入力サイズへ伸長 → mask[1,H,W] uint8(画素ごとのクラスID)。
 * VOC パレットで色付けし半透明オーバーレイ用の ARGB Bitmap を返す(背景は透明)。
 */
class SegmentationTask(
    private val engine: LiteRtEngine,
    private val labels: List<String>,
    override val label: String,
) : VisionTask {

    override val backend get() = engine.backend
    private val interp = engine.interpreter
    private val inputSize = engine.inputSize
    private val lut = engine.buildInputLut()

    private val outShape = interp.getOutputTensor(0).shape() // [1,H,W]
    private val outH = outShape[1]
    private val outW = outShape[2]

    private val inputBuf: ByteBuffer = engine.newInputBuffer()
    private val inPixels = IntArray(inputSize * inputSize)
    private val maskOut = ByteBuffer.allocateDirect(outH * outW).order(ByteOrder.nativeOrder())
    private val colorPixels = IntArray(outH * outW)
    private val colored = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

    private val palette = vocPalette(labels.size.coerceAtLeast(21))

    override fun run(upright: Bitmap): VisionResult {
        val square = ImageUtils.resizeStretch(upright, inputSize)
        engine.fillInput(square, inputBuf, inPixels, lut)
        if (square != upright) square.recycle()

        maskOut.rewind()
        interp.run(inputBuf, maskOut)
        maskOut.rewind()

        val present = HashSet<Int>()
        for (i in 0 until outH * outW) {
            val cls = maskOut.get(i).toInt() and 0xFF
            colorPixels[i] = if (cls == 0) Color.TRANSPARENT else {
                present.add(cls); palette[cls % palette.size]
            }
        }
        colored.setPixels(colorPixels, 0, outW, 0, 0, outW, outH)
        val legend = present.sorted().map { (labels.getOrElse(it) { "class$it" }) to palette[it % palette.size] }
        return VisionResult.Segmentation(colored, legend)
    }

    override fun close() = engine.close()

    private fun vocPalette(n: Int): IntArray {
        // Pascal VOC の標準カラーマップ生成(半透明 α=140)
        fun bitOf(v: Int, i: Int) = (v shr i) and 1
        return IntArray(n) { idx ->
            var r = 0; var g = 0; var b = 0; var c = idx
            for (j in 0 until 8) {
                r = r or (bitOf(c, 0) shl (7 - j))
                g = g or (bitOf(c, 1) shl (7 - j))
                b = b or (bitOf(c, 2) shl (7 - j))
                c = c shr 3
            }
            Color.argb(140, r, g, b)
        }
    }
}
