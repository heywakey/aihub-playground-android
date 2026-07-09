package com.aihub.playground.chat

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.aihub.playground.databinding.ActivityCaptureBinding
import java.io.File

/**
 * チャットを離れずに写真を撮るための全画面ライブカメラ。
 * CameraX の Preview + ImageCapture を使い、撮影した JPEG のパスを
 * [EXTRA_PHOTO_PATH] で呼び出し元に返す(RESULT_OK)。
 *
 * 端末のカメラアプリ(ACTION_IMAGE_CAPTURE)を起動する方式と違い、
 * アプリ内でプレビュー→シャッター→即添付まで完結する。
 */
class CaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptureBinding
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA
    private var capturing = false

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
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, lensFacing, preview, imageCapture)
            } catch (e: Exception) {
                Log.e(TAG, "bindToLifecycle failed", e)
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
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
