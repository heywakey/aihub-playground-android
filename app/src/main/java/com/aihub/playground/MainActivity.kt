package com.aihub.playground

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.SeekBar
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
import com.aihub.playground.vision.Adjustable
import com.aihub.playground.vision.ImageUtils
import com.aihub.playground.vision.RoiAware
import com.aihub.playground.vision.VisionTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 推論画面。ギャラリーから渡された [EXTRA_MODEL_ID] のモデルでカメラライブビュー推論する。
 * カテゴリ(検出/分類/セグ/超解像/深度)は VisionTask が吸収する。
 */
class MainActivity : AppCompatActivity() {

    companion object { const val EXTRA_MODEL_ID = "model_id" }

    private lateinit var binding: ActivityMainBinding
    private lateinit var analysisExecutor: ExecutorService

    @Volatile private var task: VisionTask? = null
    private val inferBusy = AtomicBoolean(false)

    private var frameCount = 0
    private var fpsWindowStart = 0L
    private var lastFps = 0.0
    @Volatile private var lastLatencyMs = 0L
    private lateinit var entry: CatalogEntry

    private var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA
    @Volatile private var roiTask: RoiAware? = null

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, R.string.camera_permission_rationale, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        analysisExecutor = Executors.newSingleThreadExecutor()

        val id = intent.getStringExtra(EXTRA_MODEL_ID)
        val found = ModelCatalog.load(this).firstOrNull { it.id == id }
        if (found == null) {
            binding.statusText.text = "モデルが見つかりません: $id"
            return
        }
        entry = found
        binding.titleText.text = "${entry.category ?: entry.type.name} ・ ${entry.displayName}"
        binding.backButton.setOnClickListener { finish() }
        binding.flipButton.setOnClickListener { toggleCamera() }

        // ROI(領域選択)は超解像/セグ/深度で有効化
        val roiTypes = setOf(TaskType.SUPERRES, TaskType.SEGMENTATION, TaskType.DEPTH)
        if (entry.type in roiTypes) {
            binding.overlayView.roiEnabled = true
            binding.roiHint.visibility = android.view.View.VISIBLE
        }
        if (entry.type == TaskType.DETECTION) setupThresholdPanel()

        prepareModel()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun prepareModel() {
        lifecycleScope.launch {
            try {
                // 通常はギャラリーが DL 済み。未取得で直接起動された場合はここで取得する。
                val resolved = ModelDownloader.ensure(this@MainActivity, entry) { p ->
                    val msg = if (p < 0) "ダウンロード中…" else "ダウンロード中 ${(p * 100).toInt()}%"
                    runOnUiThread { setStatus(msg) }
                }
                setStatus("推論エンジン初期化中…")
                val t = withContext(Dispatchers.Default) {
                    VisionTask.create(this@MainActivity, entry, resolved.modelFile, resolved.labelsFile)
                }
                task = t
                roiTask = t as? RoiAware
                (t as? Adjustable)?.let { a ->
                    runOnUiThread {
                        a.scoreThreshold = binding.scoreSeek.progress / 100f
                        a.iouThreshold = binding.iouSeek.progress / 100f
                    }
                }
                setStatus("backend: ${t.backend}")
            } catch (e: Throwable) {
                Log.e("MainActivity", "初期化失敗", e)
                setStatus("初期化失敗: ${e.message}")
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
            // フロントカメラはプレビューが左右反転表示されるのでオーバーレイも合わせる
            binding.overlayView.mirror = lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA
            provider.bindToLifecycle(this, lensFacing, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleCamera() {
        lensFacing = if (lensFacing == CameraSelector.DEFAULT_BACK_CAMERA)
            CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        startCamera()
    }

    private fun setupThresholdPanel() {
        binding.thresholdPanel.visibility = android.view.View.VISIBLE
        fun sync() {
            val s = binding.scoreSeek.progress / 100f
            val i = binding.iouSeek.progress / 100f
            binding.scoreLabel.text = "スコア閾値 %.2f".format(s)
            binding.iouLabel.text = "IoU 閾値 %.2f".format(i)
            (task as? Adjustable)?.let { it.scoreThreshold = s; it.iouThreshold = i }
        }
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = sync()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        binding.scoreSeek.setOnSeekBarChangeListener(listener)
        binding.iouSeek.setOnSeekBarChangeListener(listener)
        sync()
    }

    private fun analyzeFrame(image: ImageProxy) {
        val t = task
        if (t == null || !inferBusy.compareAndSet(false, true)) {
            image.close(); updateFps(); return
        }
        try {
            val t0 = SystemClock.elapsedRealtime()
            val upright = ImageUtils.toUprightBitmap(image)
            roiTask?.roi = binding.overlayView.frameRoi(upright.width, upright.height)
            val result = t.run(upright)
            lastLatencyMs = SystemClock.elapsedRealtime() - t0
            runOnUiThread { binding.overlayView.setResult(result) }
            upright.recycle()
        } catch (e: Throwable) {
            Log.e("MainActivity", "推論エラー", e)
        } finally {
            image.close()
            inferBusy.set(false)
        }
        updateFps()
    }

    private fun updateFps() {
        frameCount++
        val now = SystemClock.elapsedRealtime()
        if (fpsWindowStart == 0L) fpsWindowStart = now
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1000) {
            lastFps = frameCount * 1000.0 / elapsed
            frameCount = 0; fpsWindowStart = now
            val backend = task?.backend ?: "loading"
            runOnUiThread {
                binding.statusText.text = "%.1f fps / infer %dms / %s".format(lastFps, lastLatencyMs, backend)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        task?.close()
    }

    private fun setStatus(text: String) { runOnUiThread { binding.statusText.text = text } }
}
