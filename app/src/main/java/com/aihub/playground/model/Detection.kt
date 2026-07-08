package com.aihub.playground.model

import android.graphics.RectF

/**
 * 物体検出の1件分の結果。
 * [box] は解析フレーム(上向き Bitmap)座標系。OverlayView 側で画面座標へスケールする。
 */
data class Detection(
    val box: RectF,
    val label: String,
    val score: Float,
    val classId: Int,
)
