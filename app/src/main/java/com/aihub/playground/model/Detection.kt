package com.aihub.playground.model

import android.graphics.RectF

/**
 * 物体検出の1件分の結果。
 * [box] は解析フレーム座標系(モデル入力ではなく ImageProxy の座標系)。
 * OverlayView 側で画面座標へスケールして描画する。
 */
data class Detection(
    val box: RectF,
    val label: String,
    val score: Float,
)
