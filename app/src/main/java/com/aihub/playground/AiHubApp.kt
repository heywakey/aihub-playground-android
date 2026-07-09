package com.aihub.playground

import android.app.Application
import android.util.Log
import com.geniex.sdk.GenieXSdk

/**
 * アプリ起動時に GenieX SDK を初期化する。GenieX の LLM/VLM チャットと
 * モデルマネージャ(ダウンロード・パス解決)は初期化後に利用可能になる。
 * 視覚系(LiteRT/QNN)は GenieX とは独立して動くのでこの初期化には依存しない。
 */
class AiHubApp : Application() {

    override fun onCreate() {
        super.onCreate()
        GenieXSdk.getInstance().init(this, object : GenieXSdk.InitCallback {
            override fun onSuccess() {
                ready = true
                Log.i(TAG, "GenieX SDK initialized")
            }

            override fun onFailure(reason: String) {
                Log.e(TAG, "GenieX SDK init failed: $reason")
            }
        })
    }

    companion object {
        private const val TAG = "AiHubApp"
        /** GenieX 初期化完了フラグ。ギャラリーの取得済み判定などが参照する。 */
        @Volatile
        var ready = false
            private set
    }
}
