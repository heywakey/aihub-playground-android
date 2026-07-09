package com.aihub.playground.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * カタログエントリのモデル一式(AI Hub 配布の S3 zip)をダウンロードして
 * アプリ専用ストレージに展開する。展開済みなら再取得しない。
 *
 * 配置先: filesDir/models/<id>/ 以下に zip の中身をそのまま展開する。
 * modelFile / labelsFile はこのディレクトリからの相対パスで解決する。
 */
object ModelDownloader {
    private const val TAG = "ModelDownloader"

    data class Resolved(val modelFile: File, val labelsFile: File?)

    /** 展開先ディレクトリ(<filesDir>/models/<id>)。 */
    fun modelDir(context: Context, entry: CatalogEntry): File =
        File(context.filesDir, "models/${entry.id}")

    /** 既に展開済みか(modelFile が存在するか)。視覚系専用(modelFile が無ければ false)。 */
    fun isReady(context: Context, entry: CatalogEntry): Boolean {
        val mf = entry.modelFile ?: return false
        return File(modelDir(context, entry), mf).exists()
    }

    fun resolve(context: Context, entry: CatalogEntry): Resolved {
        val dir = modelDir(context, entry)
        val mf = requireNotNull(entry.modelFile) { "視覚系モデルには modelFile が必須: ${entry.id}" }
        return Resolved(
            modelFile = File(dir, mf),
            labelsFile = entry.labelsFile?.let { File(dir, it) },
        )
    }

    /**
     * 必要ならダウンロード+展開し、モデル/ラベルのパスを返す。
     * [onProgress] は 0.0..1.0(Content-Length 不明時は -1.0 を1回通知)。
     */
    suspend fun ensure(
        context: Context,
        entry: CatalogEntry,
        onProgress: (Float) -> Unit = {},
    ): Resolved = withContext(Dispatchers.IO) {
        val dir = modelDir(context, entry)
        if (isReady(context, entry)) {
            Log.i(TAG, "already downloaded: ${entry.id}")
            return@withContext resolve(context, entry)
        }
        dir.mkdirs()

        val url = requireNotNull(entry.downloadUrl) { "視覚系モデルには downloadUrl が必須: ${entry.id}" }
        val tmpZip = File(context.cacheDir, "${entry.id}.zip")
        try {
            download(url, tmpZip, onProgress)
            unzip(tmpZip, dir)
        } finally {
            tmpZip.delete()
        }

        val resolved = resolve(context, entry)
        check(resolved.modelFile.exists()) {
            "zip 展開後に modelFile が見つからない: ${entry.modelFile}"
        }
        Log.i(TAG, "ready: ${resolved.modelFile} (${resolved.modelFile.length()} bytes)")
        resolved
    }

    private fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        conn.connect()
        check(conn.responseCode == HttpURLConnection.HTTP_OK) {
            "ダウンロード失敗 HTTP ${conn.responseCode}: $url"
        }
        val total = conn.contentLengthLong
        if (total <= 0) onProgress(-1f)

        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var done = 0L
                while (input.read(buf).also { read = it } >= 0) {
                    output.write(buf, 0, read)
                    done += read
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        conn.disconnect()
    }

    /** zip を dir に展開(Zip Slip 対策込み)。 */
    private fun unzip(zip: File, dir: File) {
        val destRoot = dir.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val out = File(dir, entry.name).canonicalFile
                check(out.path.startsWith(destRoot.path + File.separator) || out == destRoot) {
                    "不正な zip エントリパス: ${entry.name}"
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }
}
