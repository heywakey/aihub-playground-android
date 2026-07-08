package com.aihub.playground.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.aihub.playground.model.Detection
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * AI Hub コンパイル済み YOLOX(INT8, tflite)の推論を LiteRT + QNN HTP delegate で回す。
 *
 * モデル I/O(metadata.json より):
 *  - 入力  image      [1,640,640,3] uint8(量子化: 0-255 → 0-1)
 *  - 出力  boxes      [1,8400,4]   uint8  → dequant で 640 空間の [x1,y1,x2,y2]
 *  - 出力  scores     [1,8400]     uint8  → dequant で 0..1 の信頼度(softmax 済み)
 *  - 出力  class_idx  [1,8400]     uint8  → クラス index
 *
 * box decode は済んでいるので後処理は「閾値 → クラス別 NMS → 座標逆変換」のみ。
 */
class YoloxDetector private constructor(
    private val interpreter: Interpreter,
    private val qnnDelegate: AutoCloseable?,
    private val labels: List<String>,
    val backend: String,
) : AutoCloseable {

    companion object {
        private const val TAG = "YoloxDetector"
        const val INPUT_SIZE = 640
        private const val NUM_ANCHORS = 8400

        /**
         * モデルを読み込み detector を構築する。HTP(NPU)を試し、使えなければ CPU に落とす。
         * [skelLibraryDir] は QNN backend の .so 置き場(通常 applicationInfo.nativeLibraryDir)。
         */
        fun create(
            context: Context,
            modelFile: File,
            labelsFile: File?,
        ): YoloxDetector {
            val labels = labelsFile?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
            val model = loadModel(modelFile)
            val skelDir = context.applicationInfo.nativeLibraryDir

            // まず QNN HTP を試す
            try {
                val qnn = QnnBackend.tryCreate(skelDir)
                if (qnn != null) {
                    val opts = Interpreter.Options().apply { addDelegate(qnn.delegate) }
                    val interp = Interpreter(model, opts)
                    Log.i(TAG, "backend = QNN HTP (NPU)")
                    return YoloxDetector(interp, qnn.closeable, labels, "QNN-HTP(NPU)")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "QNN HTP 初期化失敗、CPU にフォールバック: ${t.message}")
            }

            val interp = Interpreter(model, Interpreter.Options().apply { numThreads = 4 })
            Log.i(TAG, "backend = CPU")
            return YoloxDetector(interp, null, labels, "CPU")
        }

        private fun loadModel(file: File): ByteBuffer {
            FileChannel.open(file.toPath()).use { ch ->
                return ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
            }
        }
    }

    // 出力テンソルの index マップ(モデルにより順序が違うため実体から判定)
    private data class OutputMap(val boxes: Int, val scores: Int, val classIdx: Int)

    private val outMap: OutputMap = resolveOutputs()

    // 再利用バッファ
    private val inputBuf: ByteBuffer =
        ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val boxesOut = ByteBuffer.allocateDirect(NUM_ANCHORS * 4).order(ByteOrder.nativeOrder())
    private val scoresOut = ByteBuffer.allocateDirect(NUM_ANCHORS).order(ByteOrder.nativeOrder())
    private val classOut = ByteBuffer.allocateDirect(NUM_ANCHORS).order(ByteOrder.nativeOrder())

    private fun resolveOutputs(): OutputMap {
        for (i in 0 until interpreter.outputTensorCount) {
            val t = interpreter.getOutputTensor(i)
            val q = t.quantizationParams()
            Log.i(TAG, "out[$i] name=${t.name()} shape=${t.shape().joinToString(",")} " +
                "dtype=${t.dataType()} scale=${q.scale} zp=${q.zeroPoint}")
        }
        // 名前でマッピングする(class_idx は量子化スケール 0 でスケール順の判定が効かないため)。
        var boxes = -1; var scores = -1; var classIdx = -1
        val rank2 = mutableListOf<Pair<Int, Float>>()
        for (i in 0 until interpreter.outputTensorCount) {
            val t = interpreter.getOutputTensor(i)
            val name = t.name().lowercase()
            when {
                "box" in name -> boxes = i
                "class" in name || "idx" in name || "label" in name -> classIdx = i
                "score" in name || "conf" in name -> scores = i
            }
            if (t.shape().size != 3 || t.shape().last() != 4) {
                rank2.add(i to t.quantizationParams().scale)
            } else if (boxes < 0) {
                boxes = i
            }
        }
        // 名前で埋まらなかった分はスケールで補完(scores は 0 でない小スケール、class_idx はスケール 0)
        if (scores < 0 || classIdx < 0) {
            val remaining = rank2.filter { it.first != scores && it.first != classIdx }
            for ((idx, scale) in remaining) {
                if (scale > 0f && scores < 0) scores = idx
                else if (classIdx < 0) classIdx = idx
            }
        }
        require(boxes >= 0 && scores >= 0 && classIdx >= 0) {
            "出力マッピング失敗 boxes=$boxes scores=$scores class=$classIdx"
        }
        Log.i(TAG, "output map: boxes=$boxes scores=$scores classIdx=$classIdx")
        return OutputMap(boxes = boxes, scores = scores, classIdx = classIdx)
    }

    fun labelFor(idx: Int): String = labels.getOrElse(idx) { "cls$idx" }

    /**
     * [upright] を検出し、box を upright 座標系で返す(OverlayView がそのまま描画できる)。
     */
    fun detect(upright: Bitmap, scoreThreshold: Float = 0.4f, iouThreshold: Float = 0.5f): List<Detection> {
        val lb = ImageUtils.letterbox(upright, INPUT_SIZE)
        ImageUtils.fillRgbUint8(lb.bitmap, INPUT_SIZE, inputBuf, pixels)
        lb.bitmap.recycle()

        val outputs = HashMap<Int, Any>()
        boxesOut.rewind(); scoresOut.rewind(); classOut.rewind()
        outputs[outMap.boxes] = boxesOut
        outputs[outMap.scores] = scoresOut
        outputs[outMap.classIdx] = classOut
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuf), outputs)

        val boxQ = interpreter.getOutputTensor(outMap.boxes).quantizationParams()
        val scoreQ = interpreter.getOutputTensor(outMap.scores).quantizationParams()
        val classQ = interpreter.getOutputTensor(outMap.classIdx).quantizationParams()

        boxesOut.rewind(); scoresOut.rewind(); classOut.rewind()

        val candidates = ArrayList<Detection>()
        for (i in 0 until NUM_ANCHORS) {
            val sRaw = scoresOut.get(i).toInt() and 0xFF
            val score = (sRaw - scoreQ.zeroPoint) * scoreQ.scale
            if (score < scoreThreshold) continue

            val cRaw = classOut.get(i).toInt() and 0xFF
            // class_idx は量子化スケール 0(整数インデックスを直接格納)。スケール>0 の場合のみ dequant。
            val cls = if (classQ.scale > 0f) Math.round((cRaw - classQ.zeroPoint) * classQ.scale) else cRaw

            val o = i * 4
            val x1 = deq(boxesOut.get(o), boxQ)
            val y1 = deq(boxesOut.get(o + 1), boxQ)
            val x2 = deq(boxesOut.get(o + 2), boxQ)
            val y2 = deq(boxesOut.get(o + 3), boxQ)

            // 640 レターボックス空間 → upright 空間へ逆変換
            val ux1 = (minOf(x1, x2) - lb.padX) / lb.scale
            val uy1 = (minOf(y1, y2) - lb.padY) / lb.scale
            val ux2 = (maxOf(x1, x2) - lb.padX) / lb.scale
            val uy2 = (maxOf(y1, y2) - lb.padY) / lb.scale
            candidates.add(Detection(RectF(ux1, uy1, ux2, uy2), labelFor(cls), score, cls))
        }
        return nms(candidates, iouThreshold)
    }

    private fun deq(b: Byte, q: org.tensorflow.lite.Tensor.QuantizationParams): Float =
        ((b.toInt() and 0xFF) - q.zeroPoint) * q.scale

    /** クラス別の Greedy NMS。 */
    private fun nms(dets: List<Detection>, iouThreshold: Float): List<Detection> {
        val result = ArrayList<Detection>()
        dets.groupBy { it.classId }.forEach { (_, group) ->
            val sorted = group.sortedByDescending { it.score }.toMutableList()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                result.add(best)
                val it2 = sorted.iterator()
                while (it2.hasNext()) {
                    if (iou(best.box, it2.next().box) > iouThreshold) it2.remove()
                }
            }
        }
        return result
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    override fun close() {
        interpreter.close()
        qnnDelegate?.close()
    }
}
