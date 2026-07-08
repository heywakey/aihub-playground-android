package com.aihub.playground.vision

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.aihub.playground.model.Detection
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * YOLOX(AI Hub INT8)の物体検出。出力は box decode 済み:
 *  boxes[1,8400,4] uint8 → 640 空間の [x1,y1,x2,y2] / scores[1,8400] / class_idx[1,8400]。
 * 後処理は 閾値 → クラス別 NMS → letterbox 逆変換。
 */
class DetectionTask(
    private val engine: LiteRtEngine,
    private val labels: List<String>,
    override val label: String,
    private val scoreThreshold: Float = 0.4f,
    private val iouThreshold: Float = 0.5f,
) : VisionTask {

    private companion object { const val TAG = "DetectionTask"; const val NUM_ANCHORS = 8400 }

    override val backend get() = engine.backend
    private val interp = engine.interpreter
    private val inputSize = engine.inputSize
    private val lut = engine.buildInputLut()

    private data class OutputMap(val boxes: Int, val scores: Int, val classIdx: Int)
    private val outMap = resolveOutputs()

    private val inputBuf: ByteBuffer = engine.newInputBuffer()
    private val pixels = IntArray(inputSize * inputSize)
    private val boxesOut = ByteBuffer.allocateDirect(NUM_ANCHORS * 4).order(ByteOrder.nativeOrder())
    private val scoresOut = ByteBuffer.allocateDirect(NUM_ANCHORS).order(ByteOrder.nativeOrder())
    private val classOut = ByteBuffer.allocateDirect(NUM_ANCHORS).order(ByteOrder.nativeOrder())

    private fun resolveOutputs(): OutputMap {
        var boxes = -1; var scores = -1; var classIdx = -1
        val rank2 = mutableListOf<Pair<Int, Float>>()
        for (i in 0 until interp.outputTensorCount) {
            val t = interp.getOutputTensor(i)
            val name = t.name().lowercase()
            when {
                "box" in name -> boxes = i
                "class" in name || "idx" in name || "label" in name -> classIdx = i
                "score" in name || "conf" in name -> scores = i
            }
            if (t.shape().size != 3 || t.shape().last() != 4) rank2.add(i to t.quantizationParams().scale)
            else if (boxes < 0) boxes = i
        }
        if (scores < 0 || classIdx < 0) {
            for ((idx, scale) in rank2.filter { it.first != scores && it.first != classIdx }) {
                if (scale > 0f && scores < 0) scores = idx else if (classIdx < 0) classIdx = idx
            }
        }
        require(boxes >= 0 && scores >= 0 && classIdx >= 0) { "出力マッピング失敗" }
        Log.i(TAG, "output map: boxes=$boxes scores=$scores classIdx=$classIdx")
        return OutputMap(boxes, scores, classIdx)
    }

    override fun run(upright: Bitmap): VisionResult {
        val lb = ImageUtils.letterbox(upright, inputSize)
        engine.fillInput(lb.bitmap, inputBuf, pixels, lut)
        lb.bitmap.recycle()

        boxesOut.rewind(); scoresOut.rewind(); classOut.rewind()
        interp.runForMultipleInputsOutputs(
            arrayOf(inputBuf),
            hashMapOf<Int, Any>(outMap.boxes to boxesOut, outMap.scores to scoresOut, outMap.classIdx to classOut),
        )
        val boxQ = interp.getOutputTensor(outMap.boxes).quantizationParams()
        val scoreQ = interp.getOutputTensor(outMap.scores).quantizationParams()
        val classQ = interp.getOutputTensor(outMap.classIdx).quantizationParams()
        boxesOut.rewind(); scoresOut.rewind(); classOut.rewind()

        val candidates = ArrayList<Detection>()
        for (i in 0 until NUM_ANCHORS) {
            val sRaw = scoresOut.get(i).toInt() and 0xFF
            val score = (sRaw - scoreQ.zeroPoint) * scoreQ.scale
            if (score < scoreThreshold) continue
            val cRaw = classOut.get(i).toInt() and 0xFF
            val cls = if (classQ.scale > 0f) Math.round((cRaw - classQ.zeroPoint) * classQ.scale) else cRaw
            val o = i * 4
            val x1 = deq(boxesOut.get(o), boxQ); val y1 = deq(boxesOut.get(o + 1), boxQ)
            val x2 = deq(boxesOut.get(o + 2), boxQ); val y2 = deq(boxesOut.get(o + 3), boxQ)
            val ux1 = (minOf(x1, x2) - lb.padX) / lb.scale
            val uy1 = (minOf(y1, y2) - lb.padY) / lb.scale
            val ux2 = (maxOf(x1, x2) - lb.padX) / lb.scale
            val uy2 = (maxOf(y1, y2) - lb.padY) / lb.scale
            candidates.add(Detection(RectF(ux1, uy1, ux2, uy2), labels.getOrElse(cls) { "cls$cls" }, score, cls))
        }
        return VisionResult.Detections(nms(candidates), upright.width, upright.height)
    }

    private fun deq(b: Byte, q: org.tensorflow.lite.Tensor.QuantizationParams): Float =
        ((b.toInt() and 0xFF) - q.zeroPoint) * q.scale

    private fun nms(dets: List<Detection>): List<Detection> {
        val result = ArrayList<Detection>()
        dets.groupBy { it.classId }.forEach { (_, group) ->
            val sorted = group.sortedByDescending { it.score }.toMutableList()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0); result.add(best)
                val it2 = sorted.iterator()
                while (it2.hasNext()) if (iou(best.box, it2.next().box) > iouThreshold) it2.remove()
            }
        }
        return result
    }

    private fun iou(a: RectF, b: RectF): Float {
        val l = maxOf(a.left, b.left); val t = maxOf(a.top, b.top)
        val r = minOf(a.right, b.right); val btm = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, r - l) * maxOf(0f, btm - t)
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    override fun close() = engine.close()
}
