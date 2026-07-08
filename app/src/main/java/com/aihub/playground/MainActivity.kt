package com.aihub.playground

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aihub.playground.databinding.ActivityMainBinding
import com.aihub.playground.model.CatalogEntry
import com.aihub.playground.model.ModelCatalog
import com.aihub.playground.model.ModelDownloader
import com.aihub.playground.model.TaskType
import com.aihub.playground.vision.ImageUtils
import com.aihub.playground.vision.YoloxDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P1 縦貫通: モデル DL → YOLOX(LiteRT+QNN)init → CameraX フレーム解析で検出 → OverlayView。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var analysisExecutor: ExecutorService

    @Volatile private var detector: YoloxDetector? = null
    private val inferBusy = AtomicBoolean(false)

    private var frameCount = 0
    private var fpsWindowStart = 0L
    private var lastFps = 0.0
    @Volatile private var lastLatencyMs = 0L

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, R.string.camera_permission_rationale, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // カメラライブビュー中は画面を消さない
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        analysisExecutor = Executors.newSingleThreadExecutor()

        val entry = ModelCatalog.load(this).firstOrNull { it.type == TaskType.DETECTION }
        if (entry == null) {
            binding.statusText.text = "検出モデルがカタログにありません"
            return
        }
        prepareModel(entry)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    /** モデルをダウンロード(必要時)して detector を初期化する。 */
    private fun prepareModel(entry: CatalogEntry) {
        lifecycleScope.launch {
            try {
                setStatus("モデル準備中: ${entry.displayName}")
                val resolved = ModelDownloader.ensure(this@MainActivity, entry) { p ->
                    val msg = if (p < 0) "ダウンロード中…" else "ダウンロード中 ${(p * 100).toInt()}%"
                    runOnUiThread { setStatus(msg) }
                }
                setStatus("推論エンジン初期化中…")
                val det = withContext(Dispatchers.Default) {
                    YoloxDetector.create(this@MainActivity, resolved.modelFile, resolved.labelsFile)
                }
                detector = det
                setStatus("準備完了 / backend: ${det.backend}")
            } catch (t: Throwable) {
                Log.e("MainActivity", "モデル準備失敗", t)
                setStatus("モデル準備失敗: ${t.message}")
            }
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(analysisExecutor) { image -> analyzeFrame(image) } }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        val det = detector
        if (det == null || !inferBusy.compareAndSet(false, true)) {
            image.close()
            updateFps(image.width, image.height, ready = det != null)
            return
        }
        try {
            val t0 = SystemClock.elapsedRealtime()
            val upright = ImageUtils.toUprightBitmap(image)
            val results = det.detect(upright)
            lastLatencyMs = SystemClock.elapsedRealtime() - t0
            val fw = upright.width
            val fh = upright.height
            runOnUiThread { binding.overlayView.setResults(results, fw, fh) }
            upright.recycle()
        } catch (t: Throwable) {
            Log.e("MainActivity", "推論エラー", t)
        } finally {
            image.close()
            inferBusy.set(false)
        }
        updateFps(image.width, image.height, ready = true)
    }

    private fun updateFps(w: Int, h: Int, ready: Boolean) {
        frameCount++
        val now = SystemClock.elapsedRealtime()
        if (fpsWindowStart == 0L) fpsWindowStart = now
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1000) {
            lastFps = frameCount * 1000.0 / elapsed
            frameCount = 0
            fpsWindowStart = now
            val backend = detector?.backend ?: "loading"
            runOnUiThread {
                binding.statusText.text =
                    "%.1f fps / infer %dms / %s".format(lastFps, lastLatencyMs, backend)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        detector?.close()
    }

    private fun setStatus(text: String) {
        runOnUiThread { binding.statusText.text = text }
    }
}
