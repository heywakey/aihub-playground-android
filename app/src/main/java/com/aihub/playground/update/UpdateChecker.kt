package com.aihub.playground.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Play を介さない自己配布用のアップデート確認。
 *
 * 配布サーバに置いた [LATEST_JSON_URL] を取得し、そこに書かれた versionCode が
 * インストール済みより新しければ [LatestInfo] を返す(そうでなければ null)。
 * 実際の APK 取得はブラウザに [LatestInfo.apkUrl] を開かせる方式(GalleryActivity 側)。
 *
 * latest.json の例:
 * {
 *   "versionCode": 2,
 *   "versionName": "0.2",
 *   "apkUrl": "https://github.com/<user>/<repo>/releases/download/v0.2/aihub-playground-v0.2-2.apk",
 *   "notes": "・撮影→VLM の一体化\n・タップフォーカス/フラッシュ対応",
 *   "minSupportedVersionCode": 1
 * }
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"

    // TODO: 配布時に自分のホスト(GitHub Releases の raw latest.json など)に差し替える。
    const val LATEST_JSON_URL =
        "https://raw.githubusercontent.com/heywakey/aihub-playground-android/main/distribution/latest.json"

    @Serializable
    data class LatestInfo(
        val versionCode: Int,
        val versionName: String = "",
        val apkUrl: String,
        val notes: String = "",
        /** これ未満の versionCode は「強制更新(後で不可)」にしたい時に使う。省略時は 0。 */
        val minSupportedVersionCode: Int = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** インストール済みの versionCode。 */
    fun currentVersionCode(context: Context): Int {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
        } catch (e: PackageManager.NameNotFoundException) {
            -1
        }
    }

    /** 新しい版があれば LatestInfo、無ければ/取得失敗時は null。ネットワークは IO で行う。 */
    suspend fun check(context: Context): LatestInfo? = withContext(Dispatchers.IO) {
        val current = currentVersionCode(context)
        if (current < 0) return@withContext null
        val latest = runCatching { fetch(LATEST_JSON_URL) }
            .onFailure { Log.w(TAG, "latest.json 取得失敗: ${it.message}") }
            .getOrNull() ?: return@withContext null
        if (latest.versionCode > current) latest else null
    }

    /** インストール済みが強制更新ラインを下回っているか(= 後回し不可にすべきか)。 */
    fun isForced(context: Context, info: LatestInfo): Boolean =
        currentVersionCode(context) < info.minSupportedVersionCode

    private fun fetch(url: String): LatestInfo {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
        }
        try {
            conn.connect()
            check(conn.responseCode == HttpURLConnection.HTTP_OK) {
                "HTTP ${conn.responseCode}"
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return json.decodeFromString(LatestInfo.serializer(), body)
        } finally {
            conn.disconnect()
        }
    }
}
