package com.aihub.playground.vision

import android.content.Context
import android.graphics.Bitmap
import com.aihub.playground.model.CatalogEntry
import com.aihub.playground.model.Detection
import com.aihub.playground.model.TaskType
import java.io.File

/** 1フレームの推論結果。OverlayView が型ごとに描画する。 */
sealed class VisionResult {
    /** 物体検出: フレーム座標系のボックス群。 */
    data class Detections(val items: List<Detection>, val frameW: Int, val frameH: Int) : VisionResult()

    /** 画像分類: (ラベル, 確率) の上位K。 */
    data class Classification(val topK: List<Pair<String, Float>>) : VisionResult()

    /**
     * セグメンテーション: 色付きマスク(モデル解像度の ARGB、半透明)。
     * フレーム全体を model 入力へリサイズして推論しているので、フレーム全域に伸長描画する。
     */
    data class Segmentation(val colored: Bitmap, val legend: List<Pair<String, Int>>) : VisionResult()

    /** 深度: カラーマップ画像(モデル解像度)。フレーム全域に伸長描画。 */
    data class Depth(val colored: Bitmap) : VisionResult()

    /**
     * 超解像: 中央クロップを [upscaled](SR)と [bilinear](単純拡大)の両方に拡大し、
     * 左右比較でインセット表示する。元クロップ領域を [sourceRect](フレーム座標)で枠表示。
     */
    data class SuperRes(val upscaled: Bitmap, val bilinear: Bitmap, val sourceRect: android.graphics.RectF,
                        val frameW: Int, val frameH: Int) : VisionResult()
}

/** 領域選択(ROI)対応タスク。フレーム座標の矩形を毎フレーム受け取り、その領域だけ処理する。 */
interface RoiAware {
    /** フレーム座標系の処理対象矩形。null なら中央固定。 */
    var roi: android.graphics.RectF?
}

/** 検出しきい値を実行時に調整できるタスク。 */
interface Adjustable {
    var scoreThreshold: Float
    var iouThreshold: Float
}

/** カテゴリ共通の推論タスク。フレーム(上向き Bitmap)を受けて結果を返す。 */
interface VisionTask : AutoCloseable {
    val backend: String
    val label: String
    fun run(upright: Bitmap): VisionResult

    companion object {
        fun create(context: Context, entry: CatalogEntry, modelFile: File, labelsFile: File?): VisionTask {
            val engine = LiteRtEngine.create(context, modelFile)
            val labels = when {
                entry.labelsAsset != null ->
                    context.assets.open(entry.labelsAsset).bufferedReader().readLines()
                        .map { it.trim() }.filter { it.isNotEmpty() }
                labelsFile?.exists() == true ->
                    labelsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
                else -> emptyList()
            }
            return when (entry.type) {
                TaskType.DETECTION -> DetectionTask(engine, labels, entry.displayName)
                TaskType.CLASSIFICATION -> ClassificationTask(engine, labels, entry.displayName)
                TaskType.SEGMENTATION -> SegmentationTask(engine, labels, entry.displayName)
                TaskType.SUPERRES -> SuperResTask(engine, entry.displayName)
                TaskType.DEPTH -> DepthTask(engine, entry.displayName)
                TaskType.CHAT, TaskType.VLM -> error("チャット/VLM は視覚タスクではない(ChatActivity で扱う)")
            }
        }
    }
}
