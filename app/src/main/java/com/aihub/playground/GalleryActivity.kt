package com.aihub.playground

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aihub.playground.model.CatalogEntry
import com.aihub.playground.model.ModelCatalog
import com.aihub.playground.model.ModelDownloader
import kotlinx.coroutines.launch

/**
 * モデルギャラリー(ランチャー)。カタログ一覧から選び、未取得なら同意ダイアログ+
 * 進捗バー付きでダウンロードしてから推論画面(MainActivity)へ遷移する。
 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var adapter: ModelAdapter
    private lateinit var entries: List<CatalogEntry>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        entries = ModelCatalog.load(this)
        adapter = ModelAdapter(entries) { onSelect(it) }
        findViewById<RecyclerView>(R.id.modelList).apply {
            layoutManager = LinearLayoutManager(this@GalleryActivity)
            adapter = this@GalleryActivity.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged() // 戻ってきた時にダウンロード状態を更新
    }

    private fun onSelect(entry: CatalogEntry) {
        if (ModelDownloader.isReady(this, entry)) {
            launchInference(entry)
        } else {
            confirmAndDownload(entry)
        }
    }

    private fun confirmAndDownload(entry: CatalogEntry) {
        val metered = (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .isActiveNetworkMetered
        val warn = if (metered)
            "\n\n⚠ 現在モバイル通信(従量課金)の可能性があります。Wi-Fi 接続を推奨します。" else ""
        AlertDialog.Builder(this)
            .setTitle("モデルをダウンロード")
            .setMessage("${entry.displayName}(${entry.modelName})を HuggingFace/Qualcomm AI Hub から" +
                "ダウンロードします。$warn")
            .setPositiveButton("ダウンロード") { _, _ -> download(entry) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun download(entry: CatalogEntry) {
        val pos = entries.indexOf(entry)
        adapter.setDownloading(pos, 0)
        lifecycleScope.launch {
            try {
                ModelDownloader.ensure(this@GalleryActivity, entry) { p ->
                    runOnUiThread { adapter.setDownloading(pos, if (p < 0) -1 else (p * 100).toInt()) }
                }
                runOnUiThread { adapter.setDownloading(pos, null); launchInference(entry) }
            } catch (e: Throwable) {
                runOnUiThread {
                    adapter.setDownloading(pos, null)
                    AlertDialog.Builder(this@GalleryActivity)
                        .setTitle("ダウンロード失敗").setMessage(e.message ?: "不明なエラー")
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun launchInference(entry: CatalogEntry) {
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_MODEL_ID, entry.id))
    }

    // ---- Adapter ----

    private inner class ModelAdapter(
        val items: List<CatalogEntry>,
        val onClick: (CatalogEntry) -> Unit,
    ) : RecyclerView.Adapter<ModelAdapter.VH>() {

        /** pos -> 進捗(0..100, -1=不定, null=非DL中) */
        private val progress = HashMap<Int, Int?>()

        fun setDownloading(pos: Int, value: Int?) {
            if (value == null) progress.remove(pos) else progress[pos] = value
            notifyItemChanged(pos)
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val category: TextView = v.findViewById(R.id.categoryChip)
            val status: TextView = v.findViewById(R.id.statusChip)
            val name: TextView = v.findViewById(R.id.nameText)
            val sub: TextView = v.findViewById(R.id.subText)
            val bar: ProgressBar = v.findViewById(R.id.progress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_model, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            h.category.text = e.category ?: e.type.name
            h.name.text = e.displayName
            h.sub.text = "${e.modelName} ・ ${e.license}"

            val prog = progress[position]
            when {
                prog != null -> {
                    h.status.text = "↓ ダウンロード中"
                    h.status.setTextColor(0xFF4FC3F7.toInt())
                    h.bar.visibility = View.VISIBLE
                    if (prog < 0) h.bar.isIndeterminate = true
                    else { h.bar.isIndeterminate = false; h.bar.progress = prog }
                }
                ModelDownloader.isReady(this@GalleryActivity, e) -> {
                    h.status.text = "● ダウンロード済み"
                    h.status.setTextColor(0xFF66BB6A.toInt())
                    h.bar.visibility = View.GONE
                }
                else -> {
                    h.status.text = "○ 未取得"
                    h.status.setTextColor(0xFF9AA0A6.toInt())
                    h.bar.visibility = View.GONE
                }
            }
            h.itemView.setOnClickListener { if (progress[position] == null) onClick(e) }
        }
    }
}
