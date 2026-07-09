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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aihub.playground.chat.ChatActivity
import com.aihub.playground.model.CatalogEntry
import com.aihub.playground.model.ModelCatalog
import com.aihub.playground.model.ModelDownloader
import com.aihub.playground.model.TaskType
import com.geniex.sdk.ModelManagerWrapper
import kotlinx.coroutines.launch

/**
 * モデルギャラリー(ランチャー)。AI Hub 風に Vision / Multimodal / Language の
 * 3ドメイン2階層でカタログを表示する。視覚系は未取得ならダウンロードしてカメラ推論画面へ、
 * LLM/VLM はチャット画面(GenieX)へ遷移する。
 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var adapter: SectionAdapter
    private lateinit var entries: List<CatalogEntry>

    /** カテゴリ表示順・アクセント色 */
    private data class CatStyle(val order: Int, val colorRes: Int)

    private val catStyles = linkedMapOf(
        "物体検出" to CatStyle(0, R.color.accent_detection),
        "画像分類" to CatStyle(1, R.color.accent_classification),
        "セグメンテーション" to CatStyle(2, R.color.accent_segmentation),
        "超解像" to CatStyle(3, R.color.accent_superres),
        "深度推定" to CatStyle(4, R.color.accent_depth),
        "マルチモーダル" to CatStyle(5, R.color.accent_segmentation),
        "言語モデル" to CatStyle(6, R.color.accent_classification),
    )

    private fun styleFor(category: String?): CatStyle =
        catStyles[category] ?: CatStyle(99, R.color.accent_classification)

    // ---- ドメイン(大分類) ----
    private enum class Domain(val title: String, val colorRes: Int) {
        VISION("Vision", R.color.accent_superres),
        MULTIMODAL("Multimodal", R.color.accent_segmentation),
        LANGUAGE("Language", R.color.accent_classification),
    }

    private fun domainOf(type: TaskType): Domain = when (type) {
        TaskType.VLM -> Domain.MULTIMODAL
        TaskType.CHAT -> Domain.LANGUAGE
        else -> Domain.VISION
    }

    private fun isChat(entry: CatalogEntry) = entry.type == TaskType.CHAT || entry.type == TaskType.VLM

    // 表示行: ドメインヘッダ / カテゴリヘッダ / モデル
    private sealed class Row {
        data class DomainHeader(val title: String, val count: Int, val colorRes: Int) : Row()
        data class CategoryHeader(val title: String, val count: Int, val colorRes: Int) : Row()
        data class Model(val entry: CatalogEntry) : Row()
    }

    private lateinit var rows: List<Row>

    /** LLM/VLM の取得済みキャッシュ(id -> 取得済み)。onResume で非同期更新する。 */
    private val chatReadyMap = HashMap<String, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        entries = ModelCatalog.load(this)
        rows = buildRows(entries)
        adapter = SectionAdapter()
        findViewById<RecyclerView>(R.id.modelList).apply {
            layoutManager = LinearLayoutManager(this@GalleryActivity)
            adapter = this@GalleryActivity.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged() // 戻ってきた時にダウンロード状態を更新
        refreshChatReadiness()
    }

    /** LLM/VLM の取得済みを GenieX モデルマネージャに問い合わせてキャッシュ更新。 */
    private fun refreshChatReadiness() {
        if (!AiHubApp.ready) return
        val chatEntries = entries.filter { isChat(it) }
        if (chatEntries.isEmpty()) return
        lifecycleScope.launch {
            var changed = false
            for (e in chatEntries) {
                val ready = runCatching { ModelManagerWrapper.getPaths(e.modelName) != null }
                    .getOrDefault(false)
                if (chatReadyMap[e.id] != ready) { chatReadyMap[e.id] = ready; changed = true }
            }
            if (changed) adapter.notifyDataSetChanged()
        }
    }

    private fun buildRows(items: List<CatalogEntry>): List<Row> {
        val out = ArrayList<Row>()
        for (domain in Domain.values()) {
            val inDomain = items.filter { domainOf(it.type) == domain }
            if (inDomain.isEmpty()) continue
            out.add(Row.DomainHeader(domain.title, inDomain.size, domain.colorRes))
            // ドメイン内でカテゴリごとにまとめる(Vision は複数カテゴリ、他は1つ)
            val byCat = inDomain.groupBy { it.category ?: it.type.name }
            val orderedCats = byCat.keys.sortedBy { styleFor(it).order }
            val multiCat = orderedCats.size > 1
            for (cat in orderedCats) {
                val group = byCat.getValue(cat)
                if (multiCat) out.add(Row.CategoryHeader(cat, group.size, styleFor(cat).colorRes))
                group.forEach { out.add(Row.Model(it)) }
            }
        }
        return out
    }

    private fun onSelect(entry: CatalogEntry) {
        if (isChat(entry)) {
            startActivity(Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_MODEL_ID, entry.id))
            return
        }
        if (ModelDownloader.isReady(this, entry)) launchInference(entry)
        else confirmAndDownload(entry)
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
        val pos = rows.indexOfFirst { it is Row.Model && it.entry === entry }
        if (pos < 0) return
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

    private inner class SectionAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_DOMAIN = 0
        private val TYPE_CATEGORY = 1
        private val TYPE_MODEL = 2

        /** row位置 -> 進捗(0..100, -1=不定, null=非DL中) */
        private val progress = HashMap<Int, Int?>()

        fun setDownloading(pos: Int, value: Int?) {
            if (value == null) progress.remove(pos) else progress[pos] = value
            notifyItemChanged(pos)
        }

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.DomainHeader -> TYPE_DOMAIN
            is Row.CategoryHeader -> TYPE_CATEGORY
            is Row.Model -> TYPE_MODEL
        }

        inner class DomainVH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.domainTitle)
            val count: TextView = v.findViewById(R.id.domainCount)
            val rule: View = v.findViewById(R.id.domainRule)
        }

        inner class CategoryVH(v: View) : RecyclerView.ViewHolder(v) {
            val bar: View = v.findViewById(R.id.accentBar)
            val title: TextView = v.findViewById(R.id.sectionTitle)
            val count: TextView = v.findViewById(R.id.sectionCount)
        }

        inner class ModelVH(v: View) : RecyclerView.ViewHolder(v) {
            val accentEdge: View = v.findViewById(R.id.accentEdge)
            val name: TextView = v.findViewById(R.id.nameText)
            val sub: TextView = v.findViewById(R.id.subText)
            val pill: TextView = v.findViewById(R.id.statusPill)
            val bar: ProgressBar = v.findViewById(R.id.progress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_DOMAIN -> DomainVH(inf.inflate(R.layout.item_domain_header, parent, false))
                TYPE_CATEGORY -> CategoryVH(inf.inflate(R.layout.item_section_header, parent, false))
                else -> ModelVH(inf.inflate(R.layout.item_model_card, parent, false))
            }
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.DomainHeader -> {
                    h as DomainVH
                    val color = ContextCompat.getColor(this@GalleryActivity, row.colorRes)
                    h.title.text = row.title
                    h.count.text = "${row.count} モデル"
                    h.rule.setBackgroundColor(color)
                }
                is Row.CategoryHeader -> {
                    h as CategoryVH
                    h.bar.setBackgroundColor(ContextCompat.getColor(this@GalleryActivity, row.colorRes))
                    h.title.text = row.title
                    h.count.text = row.count.toString()
                }
                is Row.Model -> bindModel(h as ModelVH, row.entry, position)
            }
        }

        private fun bindModel(h: ModelVH, e: CatalogEntry, position: Int) {
            val accent = ContextCompat.getColor(this@GalleryActivity, styleFor(e.category).colorRes)
            h.accentEdge.setBackgroundColor(accent)
            h.name.text = e.displayName
            h.sub.text = "${e.modelName} ・ ${e.license}"

            val prog = progress[position]
            when {
                prog != null -> {
                    h.pill.text = "↓ ダウンロード中"
                    h.pill.setTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.status_dl))
                    h.bar.visibility = View.VISIBLE
                    if (prog < 0) h.bar.isIndeterminate = true
                    else { h.bar.isIndeterminate = false; h.bar.progress = prog }
                }
                isChat(e) -> {
                    h.bar.visibility = View.GONE
                    if (chatReadyMap[e.id] == true) {
                        h.pill.text = "✓ 取得済み"
                        h.pill.setTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.status_ready))
                    } else {
                        h.pill.text = "→ 開く"
                        h.pill.setTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.status_dl))
                    }
                }
                ModelDownloader.isReady(this@GalleryActivity, e) -> {
                    h.pill.text = "✓ 取得済み"
                    h.pill.setTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.status_ready))
                    h.bar.visibility = View.GONE
                }
                else -> {
                    h.pill.text = "○ 未取得"
                    h.pill.setTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.status_none))
                    h.bar.visibility = View.GONE
                }
            }
            h.itemView.setOnClickListener { if (progress[position] == null) onSelect(e) }
        }
    }
}
