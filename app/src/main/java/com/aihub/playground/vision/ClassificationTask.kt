package com.aihub.playground.vision

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * 画像分類(MobileNet-v2 等)。中央正方形を切り出して入力サイズへ縮小し、
 * class_logits[1,N] uint8 を dequant → softmax → 上位5件。
 */
class ClassificationTask(
    private val engine: LiteRtEngine,
    private val labels: List<String>,
    override val label: String,
    private val topK: Int = 5,
) : VisionTask {

    override val backend get() = engine.backend
    private val interp = engine.interpreter
    private val inputSize = engine.inputSize
    private val lut = engine.buildInputLut()

    private val numClasses = interp.getOutputTensor(0).shape().last()
    private val inputBuf: ByteBuffer = engine.newInputBuffer()
    private val pixels = IntArray(inputSize * inputSize)
    private val logitsOut = ByteBuffer.allocateDirect(numClasses).order(ByteOrder.nativeOrder())

    override fun run(upright: Bitmap): VisionResult {
        val crop = ImageUtils.centerCropSquare(upright, inputSize)
        engine.fillInput(crop.bitmap, inputBuf, pixels, lut)
        crop.bitmap.recycle()

        logitsOut.rewind()
        interp.run(inputBuf, logitsOut)
        val q = interp.getOutputTensor(0).quantizationParams()
        logitsOut.rewind()

        // dequant → softmax
        val logits = FloatArray(numClasses) { ((logitsOut.get(it).toInt() and 0xFF) - q.zeroPoint) * q.scale }
        val max = logits.max()
        var sum = 0f
        val probs = FloatArray(numClasses) { val e = exp(logits[it] - max); sum += e; e }
        for (i in probs.indices) probs[i] /= sum

        val top = probs.indices.sortedByDescending { probs[it] }.take(topK)
            .map { (labels.getOrElse(it) { "class$it" }) to probs[it] }
        return VisionResult.Classification(top)
    }

    override fun close() = engine.close()
}
