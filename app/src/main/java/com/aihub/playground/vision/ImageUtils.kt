package com.aihub.playground.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/** CameraX ImageProxy / Bitmap まわりの前処理ヘルパ。 */
object ImageUtils {

    /**
     * RGBA_8888 形式の ImageProxy を、回転を適用した「上向き」Bitmap に変換する。
     * ImageAnalysis.Builder に OUTPUT_IMAGE_FORMAT_RGBA_8888 を指定していることが前提。
     */
    fun toUprightBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride     // RGBA なので 4
        val rowStride = plane.rowStride         // 行パディングを含む
        val rowPadding = rowStride - pixelStride * image.width

        // rowStride 分の幅を持つ Bitmap に一括コピーしてから幅を切り詰める
        val paddedWidth = image.width + rowPadding / pixelStride
        val full = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        full.copyPixelsFromBuffer(buffer)

        val cropped = if (paddedWidth != image.width) {
            Bitmap.createBitmap(full, 0, 0, image.width, image.height)
        } else {
            full
        }

        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return cropped
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
    }

    /**
     * アスペクト比を保って [size]x[size] にレターボックス配置する。
     * 返り値の [LetterboxResult] は元座標への逆変換に使う scale / pad を保持する。
     */
    data class LetterboxResult(val bitmap: Bitmap, val scale: Float, val padX: Float, val padY: Float)

    fun letterbox(src: Bitmap, size: Int, padColor: Int = Color.rgb(114, 114, 114)): LetterboxResult {
        val scale = minOf(size.toFloat() / src.width, size.toFloat() / src.height)
        val newW = Math.round(src.width * scale)
        val newH = Math.round(src.height * scale)
        val padX = (size - newW) / 2f
        val padY = (size - newH) / 2f

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(padColor)
        val dst = Rect(padX.toInt(), padY.toInt(), padX.toInt() + newW, padY.toInt() + newH)
        canvas.drawBitmap(src, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        return LetterboxResult(out, scale, padX, padY)
    }

    /** 中央の最大正方形を切り出し [size]x[size] に縮小する。返り値の [CropResult] は逆変換用。 */
    data class CropResult(val bitmap: Bitmap, val srcLeft: Int, val srcTop: Int, val srcSide: Int)

    fun centerCropSquare(src: Bitmap, size: Int): CropResult {
        val side = minOf(src.width, src.height)
        val left = (src.width - side) / 2
        val top = (src.height - side) / 2
        val square = Bitmap.createBitmap(src, left, top, side, side)
        val scaled = Bitmap.createScaledBitmap(square, size, size, true)
        if (scaled != square) square.recycle()
        return CropResult(scaled, left, top, side)
    }

    /** フレーム全体を [size]x[size] に伸長(アスペクト無視)。full-frame→full-frame マップ用。 */
    fun resizeStretch(src: Bitmap, size: Int): Bitmap =
        Bitmap.createScaledBitmap(src, size, size, true)

    /**
     * ROI(フレーム座標の矩形)を切り出して [size]x[size] へ縮小する。ROI が null なら中央最大正方形。
     * ROI は正方に近い前提だが、はみ出しは clamp する(アスペクト無視の伸長でモデル入力に合わせる)。
     */
    fun cropRoiSquare(src: Bitmap, roi: android.graphics.RectF?, size: Int): Bitmap {
        val l: Int; val t: Int; val w: Int; val h: Int
        if (roi != null) {
            l = roi.left.toInt().coerceIn(0, src.width - 1)
            t = roi.top.toInt().coerceIn(0, src.height - 1)
            w = (roi.width().toInt()).coerceIn(1, src.width - l)
            h = (roi.height().toInt()).coerceIn(1, src.height - t)
        } else {
            val side = minOf(src.width, src.height)
            l = (src.width - side) / 2; t = (src.height - side) / 2; w = side; h = side
        }
        val patch = Bitmap.createBitmap(src, l, t, w, h)
        val scaled = Bitmap.createScaledBitmap(patch, size, size, true)
        if (scaled != patch) patch.recycle()
        return scaled
    }

    /**
     * ARGB_8888 Bitmap を、モデル入力用の uint8 RGB バッファ([1,size,size,3])に詰める。
     * バッファは呼び出し側で確保・再利用する(GC 削減)。
     */
    fun fillRgbUint8(bitmap: Bitmap, size: Int, out: ByteBuffer, pixels: IntArray) {
        out.rewind()
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        for (p in pixels) {
            out.put(((p shr 16) and 0xFF).toByte()) // R
            out.put(((p shr 8) and 0xFF).toByte())  // G
            out.put((p and 0xFF).toByte())          // B
        }
        out.rewind()
    }
}
