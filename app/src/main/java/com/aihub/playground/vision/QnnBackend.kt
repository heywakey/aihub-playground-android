package com.aihub.playground.vision

import android.util.Log
import com.qualcomm.qti.QnnDelegate

/**
 * QNN HTP(Hexagon NPU)delegate の生成をまとめる。
 * qnn-runtime AAR が同梱する libQnnHtp*.so / libQnnHtpV79Skel.so 等は APK の
 * nativeLibraryDir に展開されるので、[skelLibraryDir] にそのパスを渡す。
 */
object QnnBackend {
    private const val TAG = "QnnBackend"

    class Handle(val delegate: QnnDelegate) : AutoCloseable {
        val closeable = AutoCloseable { delegate.close() }
        override fun close() = delegate.close()
    }

    /** 生成できなければ null(この端末で HTP が使えない/初期化失敗)。 */
    fun tryCreate(skelLibraryDir: String): Handle? {
        return try {
            val options = QnnDelegate.Options().apply {
                setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
                setSkelLibraryDir(skelLibraryDir)
                setHtpPerformanceMode(
                    QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_BURST
                )
                setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_QUANTIZED)
                setLogLevel(QnnDelegate.Options.LogLevel.LOG_LEVEL_WARN)
            }
            val delegate = QnnDelegate(options)
            Log.i(TAG, "QNN version = ${QnnDelegate.getVersion()?.joinToString(".")}")
            Handle(delegate)
        } catch (t: Throwable) {
            Log.w(TAG, "QNN HTP delegate 生成失敗: ${t.message}")
            null
        }
    }
}
