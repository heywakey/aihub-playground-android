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
) : VisionTask, RoiAware {

    @Volatile override var roi: android.graphics.RectF? = null

    override val backend get() = engine.backend
    private val interp = engine.interpreter
    private val inputSize = engine.inputSize
    private val lut = engine.buildInputLut()

    // 出力は [1,H,W](クラスID直: DeepLab)か [1,H,W,C](ロジット: Segformer/FFNet)。
    private val outShape = interp.getOutputTensor(0).shape()
    private val outH = outShape[1]
    private val outW = outShape[2]
    private val numChannels = if (outShape.size >= 4) outShape[3] else 1
    private val isLogits = numChannels > 1

    private val inputBuf: ByteBuffer = engine.newInputBuffer()
    private val inPixels = IntArray(inputSize * inputSize)
    private val maskOut = ByteBuffer.allocateDirect(outH * outW * numChannels).order(ByteOrder.nativeOrder())
    private val colorPixels = IntArray(outH * outW)
    private val colored = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

    private val palette = vocPalette(maxOf(labels.size, numChannels, 21))

    // 背景として透過するクラス。VOC は index0="BACKGROUND" だが ADE20K は index0="wall" で
    // 実クラス。ラベル名で判定し、名前が背景系のクラスだけ透過する(名前が無ければ index0 を背景扱い)。
    private fun isBackground(cls: Int): Boolean {
        val name = labels.getOrNull(cls)?.lowercase()
        return if (name != null) name == "background" || name == "unlabeled" || name == "unlabelled"
        else cls == 0
    }

    override fun run(upright: Bitmap): VisionResult {
        // ROI(領域選択)を正方に切り出してモデル入力へ。null なら中央最大正方形(アスペクト無視の
        // 全画面伸長をやめ、正方クロップにしたので歪まない)。
        val square = ImageUtils.cropRoiSquare(upright, roi, inputSize)
        engine.fillInput(square, inputBuf, inPixels, lut)
        square.recycle()

        maskOut.rewind()
        interp.run(inputBuf, maskOut)
        maskOut.rewind()

        val total = outH * outW
        val counts = HashMap<Int, Int>()
        for (i in 0 until total) {
            val cls = if (isLogits) argmaxAt(i) else (maskOut.get(i).toInt() and 0xFF)
            colorPixels[i] = if (isBackground(cls)) Color.TRANSPARENT else {
                counts[cls] = (counts[cls] ?: 0) + 1; palette[cls % palette.size]
            }
        }
        colored.setPixels(colorPixels, 0, outW, 0, 0, outW, outH)
        // 面積の大きい順に凡例(名前 + 占有率%)。ADE20K は多クラスなので上位のみ。
        val legend = counts.entries.sortedByDescending { it.value }.take(8)
            .map { e ->
                val name = labels.getOrElse(e.key) { "class${e.key}" }
                val pct = (e.value * 100f / total).toInt()
                "$name $pct%" to palette[e.key % palette.size]
            }
        return VisionResult.Segmentation(colored, legend)
    }

    /** ピクセル i の [numChannels] 個のロジット(uint8)から argmax。 */
    private fun argmaxAt(i: Int): Int {
        val base = i * numChannels
        var best = 0; var bestV = -1
        for (c in 0 until numChannels) {
            val v = maskOut.get(base + c).toInt() and 0xFF
            if (v > bestV) { bestV = v; best = c }
        }
        return best
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
