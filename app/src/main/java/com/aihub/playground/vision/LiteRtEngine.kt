package com.aihub.playground.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * LiteRT(TFLite)Interpreter を QNN HTP delegate(NPU)優先で生成する共通エンジン。
 * HTP が使えなければ CPU にフォールバックする。全カテゴリのタスクがこれを使う。
 *
 * 入力は AI Hub モデルの慣習に合わせ io_type=image / value_range[0,1] を前提とし、
 * 入力テンソルの量子化パラメータ(scale/zp)から再量子化 LUT を作って uint8 で詰める。
 */
class LiteRtEngine private constructor(
    val interpreter: Interpreter,
    private val qnnDelegate: AutoCloseable?,
    val backend: String,
) : AutoCloseable {

    companion object {
        private const val TAG = "LiteRtEngine"

        fun create(context: Context, modelFile: File): LiteRtEngine {
            val model = loadModel(modelFile)
            val skelDir = context.applicationInfo.nativeLibraryDir
            try {
                val qnn = QnnBackend.tryCreate(skelDir)
                if (qnn != null) {
                    val interp = Interpreter(model, Interpreter.Options().apply { addDelegate(qnn.delegate) })
                    Log.i(TAG, "backend = QNN HTP (NPU)")
                    return LiteRtEngine(interp, qnn.closeable, "QNN-HTP(NPU)")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "QNN HTP 初期化失敗、CPU にフォールバック: ${t.message}")
            }
            val interp = Interpreter(model, Interpreter.Options().apply { numThreads = 4 })
            Log.i(TAG, "backend = CPU")
            return LiteRtEngine(interp, null, "CPU")
        }

        private fun loadModel(file: File): ByteBuffer {
            FileChannel.open(file.toPath()).use { ch ->
                return ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
            }
        }
    }

    /** 入力テンソルの形状 [1,H,W,3] から入力の一辺(正方入力前提)を返す。 */
    val inputSize: Int
        get() {
            val s = interpreter.getInputTensor(0).shape()
            return s[1] // H
        }

    /**
     * ピクセル値(0-255)→ 入力テンソルの量子化値(uint8)への LUT。
     * real = pixel/255(value_range[0,1])とし q = round(real/scale + zp) をクランプ。
     */
    fun buildInputLut(): ByteArray {
        val q = interpreter.getInputTensor(0).quantizationParams()
        val scale = if (q.scale > 0f) q.scale else 1f / 255f
        val zp = q.zeroPoint
        return ByteArray(256) { p ->
            val real = p / 255f
            val v = Math.round(real / scale) + zp
            v.coerceIn(0, 255).toByte()
        }
    }

    /**
     * [square] (inputSize×inputSize の ARGB Bitmap)を LUT で uint8 RGB に詰める。
     * [out] は inputSize*inputSize*3 の direct ByteBuffer(再利用)。
     */
    fun fillInput(square: Bitmap, out: ByteBuffer, pixels: IntArray, lut: ByteArray) {
        val size = inputSize
        out.rewind()
        square.getPixels(pixels, 0, size, 0, 0, size, size)
        for (p in pixels) {
            out.put(lut[(p shr 16) and 0xFF]) // R
            out.put(lut[(p shr 8) and 0xFF])  // G
            out.put(lut[p and 0xFF])          // B
        }
        out.rewind()
    }

    fun newInputBuffer(): ByteBuffer =
        ByteBuffer.allocateDirect(inputSize * inputSize * 3).order(ByteOrder.nativeOrder())

    override fun close() {
        interpreter.close()
        qnnDelegate?.close()
    }
}
