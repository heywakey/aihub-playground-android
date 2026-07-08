package com.aihub.playground

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.aihub.playground.databinding.ActivityMainBinding
import com.aihub.playground.model.ModelCatalog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * P1 骨組み: CameraX プレビュー + フレーム解析(現状は FPS 計測のみ)+ オーバーレイ。
 * 次のステップで ImageAnalysis の analyzer を YOLOX (LiteRT + QNN) 推論に差し替える。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var analysisExecutor: ExecutorService

    private var frameCount = 0
    private var fpsWindowStart = 0L
    private var lastFps = 0.0

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.camera_permission_rationale, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        analysisExecutor = Executors.newSingleThreadExecutor()

        val catalog = ModelCatalog.load(this)
        binding.statusText.text = "models in catalog: ${catalog.size} / camera: -"

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
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
                .build()
                .also { it.setAnalyzer(analysisExecutor) { image -> analyzeFrame(image) } }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }, ContextCompat.getMainExecutor(this))
    }

    /** 現状はFPS計測のみ。ここに YOLOX 前処理→LiteRT 推論→NMS を実装する(P1)。 */
    private fun analyzeFrame(image: ImageProxy) {
        image.use {
            frameCount++
            val now = SystemClock.elapsedRealtime()
            if (fpsWindowStart == 0L) fpsWindowStart = now
            val elapsed = now - fpsWindowStart
            if (elapsed >= 1000) {
                lastFps = frameCount * 1000.0 / elapsed
                frameCount = 0
                fpsWindowStart = now
                runOnUiThread {
                    binding.statusText.text =
                        "camera: %.1f fps (%dx%d) / model: none".format(
                            lastFps, image.width, image.height,
                        )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}
