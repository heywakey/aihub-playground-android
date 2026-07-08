package com.aihub.playground.model

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * assets/model_list.json のエントリ。
 * geniex-chat-android のカタログ方式を視覚系モデル向けに拡張したもの。
 * AI Hub モデルは HF リポの release_assets.json が指す S3 zip で配布されるため、
 * downloadUrl(zip)+ zip 内パス(modelFile / labelsFile)で表現する。
 * license は Google Play 配布方針(Apache-2.0 / MIT 系のみ)のレビュー用に必須。
 */
@Serializable
data class CatalogEntry(
    val id: String,
    val displayName: String,
    /** 日本語のカテゴリ名(ギャラリー表示用)。未指定なら type から導出 */
    val category: String? = null,
    /** 出自の HuggingFace リポジトリ名(例: qualcomm/Yolo-X)。表示・出典用 */
    val modelName: String,
    /** モデル一式 zip の直接ダウンロード URL */
    val downloadUrl: String,
    /** zip 内のモデルファイルパス(例: yolox-tflite-w8a8/yolox.tflite) */
    val modelFile: String,
    /** zip 内のラベルファイルパス(検出・分類系のみ) */
    val labelsFile: String? = null,
    val type: TaskType,
    val runtime: Runtime,
    val license: String,
    val input: InputSpec? = null,
)

@Serializable
enum class TaskType {
    @SerialName("detection") DETECTION,
    @SerialName("classification") CLASSIFICATION,
    @SerialName("segmentation") SEGMENTATION,
    @SerialName("superres") SUPERRES,
    @SerialName("depth") DEPTH,
    @SerialName("chat") CHAT,
}

@Serializable
enum class Runtime {
    @SerialName("litert_qnn") LITERT_QNN,
    @SerialName("onnx_qnn") ONNX_QNN,
    @SerialName("genie") GENIE,
    @SerialName("llama_cpp") LLAMA_CPP,
}

@Serializable
data class InputSpec(
    /** [height, width] */
    val size: List<Int>,
    /** "0-1" | "0-255" | "imagenet" */
    val norm: String = "0-1",
)

object ModelCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): List<CatalogEntry> =
        context.assets.open("model_list.json").bufferedReader().use { reader ->
            json.decodeFromString<List<CatalogEntry>>(reader.readText())
        }
}
