package com.aihub.playground.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.aihub.playground.R
import com.aihub.playground.databinding.ActivityCaptureBinding
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * チャットを離れずに写真を撮るための全画面ライブカメラ。
 * CameraX の Preview + ImageCapture を使い、撮影した JPEG のパスを
 * [EXTRA_PHOTO_PATH] で呼び出し元に返す(RESULT_OK)。
 *
 * 端末のカメラアプリ(ACTION_IMAGE_CAPTURE)を起動する方式と違い、
 * アプリ内でプレビュー→タップフォーカス/フラッシュ切替→シャッター→即添付まで完結する。
 */
class CaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptureBinding
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA
    private var capturing = false

    /** フラッシュは OFF→ON→AUTO を循環する。ImageCapture.flashMode に反映。 */
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }
        binding.btnShutter.setOnClickListener { takePhoto() }
        binding.btnFlip.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.DEFAULT_BACK_CAMERA)
                CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            bindCamera()
        }
        binding.btnFlash.setOnClickListener { cycleFlash() }

        setupTapToFocus()
        bindCamera()
    }

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(flashMode)
                .build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, lensFacing, preview, imageCapture)
                updateFlashButton()
            } catch (e: Exception) {
                Log.e(TAG, "bindToLifecycle failed", e)
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** フラッシュ状態を循環させ、ImageCapture とボタン表示に反映する。 */
    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        updateFlashButton()
    }

    /** 前面カメラ等フラッシュ非搭載時はボタンを無効化。アイコンは状態に追従。 */
    private fun updateFlashButton() {
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
        binding.btnFlash.isEnabled = hasFlash
        binding.btnFlash.alpha = if (hasFlash) 1f else 0.35f
        binding.btnFlash.setImageResource(
            when (flashMode) {
                ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
                ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
                else -> R.drawable.ic_flash_off
            }
        )
    }

    /** プレビューをタップした位置で AF/AE を実行し、合焦枠を一瞬表示する。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToFocus() {
        binding.previewView.setOnTouchListener { _, event ->
            when (event.action) {
                // DOWN で true を返さないと後続の UP が配信されない
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_UP -> {
                    val cam = camera
                    if (cam != null) {
                        val point = binding.previewView.meteringPointFactory
                            .createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(point)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build()
                        cam.cameraControl.startFocusAndMetering(action)
                        showFocusRing(event.x, event.y)
                    }
                    binding.previewView.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        val ring = binding.focusRing
        // visibility=gone のうちは width/height が 0 なので dp から実サイズを求めて中央寄せする
        val half = 36f * resources.displayMetrics.density // 72dp の半分
        ring.x = x - half
        ring.y = y - half
        ring.visibility = View.VISIBLE
        ring.alpha = 1f
        ring.scaleX = 1.3f
        ring.scaleY = 1.3f
        ring.animate()
            .scaleX(1f).scaleY(1f).alpha(0.9f)
            .setDuration(220)
            .withEndAction {
                ring.postDelayed({
                    ring.animate().alpha(0f).setDuration(200)
                        .withEndAction { ring.visibility = View.GONE }.start()
                }, 1000)
            }
            .start()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (capturing) return
        capturing = true
        binding.btnShutter.isEnabled = false

        val photoFile = File(
            getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "capture_${System.currentTimeMillis()}.jpg"
        )
        val options = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val data = Intent().putExtra(EXTRA_PHOTO_PATH, photoFile.absolutePath)
                    setResult(Activity.RESULT_OK, data)
                    finish()
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "takePicture failed", exc)
                    capturing = false
                    binding.btnShutter.isEnabled = true
                }
            }
        )
    }

    companion object {
        private const val TAG = "CaptureActivity"
        const val EXTRA_PHOTO_PATH = "photo_path"
    }
}
