# AI Hub Playground Android — 設計文書

2026-07-08 時点。geniex-chat-android 側セッションでの議論の成果物。

## ゴール

Qualcomm AI Hub にあるモデルを幅広く体験できる単一の Android アプリ。
Ultralytics アプリのような UX:

1. モデルギャラリー(カテゴリ別)からモデルを選ぶ
2. 未取得ならその場で HuggingFace からダウンロード
3. カメラライブビュー(またはチャット UI)で即実行、結果をオーバーレイ表示

「全モデル対応」ではなく「**タスクカテゴリ対応**」が設計方針。
同一カテゴリ内のモデルは前処理・後処理が共通なので、カテゴリの
パイプラインを1本書けばモデル差し替えだけで横展開できる。

## 基準デバイス

Galaxy Z Fold 7(Snapdragon 8 Elite for Galaxy / Hexagon NPU / RAM 12GB)。
視覚モデルはリアルタイム(数ms〜数十ms/フレーム)、LLM は 3B 級まで快適の見込み。
minSdk 31 / targetSdk 34(geniex-chat-android に合わせる)。

## アーキテクチャ

### ランタイム(1 APK に共存)

| 用途 | ランタイム | 備考 |
|---|---|---|
| 視覚系全般 | LiteRT (TFLite) + QNN delegate | Hexagon NPU 実行。ai-hub-apps サンプルと同構成 |
| LLM チャット | GenieX (`com.qualcomm.qti:geniex-android`) | geniex-chat-android の実装を移植(Phase 3) |
| (代替) | ONNX Runtime + QNN EP | LiteRT で不都合が出たモデル用の逃げ道 |

### タスクカテゴリとパイプライン

カテゴリごとに「前処理 → 推論 → 後処理 → 描画」を実装する。
推論部は共通、前後処理がカテゴリ固有。

| カテゴリ | 後処理 | 難易度 | フェーズ |
|---|---|---|---|
| 物体検出 | box decode + NMS | 中 | P1 |
| 画像分類 | argmax + ラベル | 低 | P2 |
| セグメンテーション | マスク描画 | 中 | P2 |
| 超解像 | 画像表示 | 低 | P2 |
| 深度推定 | カラーマップ表示 | 低 | P2 |
| LLM チャット | (GenieX 実装済み) | 済 | P3 |
| ポーズ推定 | キーポイント描画 | 中 | P3以降 |
| 音声認識 (Whisper) | 2モデル構成+トークナイザ | 高 | P4 |
| 画像生成 (SD) | 複数モデルパイプライン | 高 | P4 |

### モデルカタログ

geniex-chat-android の `model_list.json` 方式を拡張。エントリ例:

```json
{
  "id": "yolox-tiny",
  "displayName": "YOLOX-Tiny",
  "modelName": "qualcomm/YOLOX",
  "hub": "HUGGINGFACE",
  "type": "detection",          // detection | classification | segmentation | superres | depth | chat
  "runtime": "litert_qnn",      // litert_qnn | llama_cpp | genie | onnx_qnn
  "license": "Apache-2.0",      // カタログ採用の必須条件(下記ライセンス方針)
  "input": { "size": [416, 416], "norm": "0-1" },
  "labels": "coco80"
}
```

- モデルは APK 同梱せず実行時ダウンロード(同意 UI+Wi-Fi 警告を付ける)
- `license` フィールドを必須にし、カタログ追加時にレビューする

## ライセンス方針(Google Play 配布を見据える)

- **アプリコード**: ai-hub-apps 由来部分は BSD-3-Clause →
  LICENSE/NOTICE 同梱で配布自由(geniex-chat-android と同じ方式)
- **モデル**: カタログは **Apache-2.0 / MIT のみ**
  - ❌ Ultralytics YOLO v8/v11(AGPL-3.0 — 配布でソース開示義務、商用ライセンス回避)
  - ✅ 検出は YOLOX(Apache-2.0)
  - ✅ LLM: Qwen3 / Granite(Apache-2.0)、Phi-4-mini(MIT)
  - ⚠️ Ministral 系(Mistral 独自ライセンス)は外す
- **ランタイム**: llama.cpp は MIT。GenieX/QNN は Maven Central 配布物であり
  アプリ組込み配布が前提のものだが、公開前に POM のライセンス条項を確認する
- モデルを実行時 DL 方式にすることで、APK として再配布するのはコードのみになる
- Play 公開時の実務: デベロッパー登録 $25、個人アカウントは
  クローズドテスト要件(20人×2週間)あり

## ビルド基盤

geniex-chat-android の `scripts/` を移植:

- `setup.sh` — JDK17 + Android SDK + Gradle をリポ内に導入(gitignore)
- `build.sh` — debug APK / `release.sh` — 署名付き release(keystore 自動生成)
- `rebuild.sh` / `resetup.sh` / `memwatch.sh`
- SDK・Gradle・build 出力・keystore・dist はすべて gitignore。リポはソース+スクリプトのみ

## フェーズ計画

1. **P1 縦貫通**: scripts 移植 → Gradle 雛形 → CameraX+オーバーレイ →
   LiteRT+QNN で YOLOX 検出を Fold 7 実機で動かす(ここが最重量)
2. **P2 横展開**: 分類・セグメンテーション・超解像・深度(後処理の追加が中心)
3. **P3 統合**: GenieX チャット移植、モデルギャラリー UI 整備
4. **P4 任意**: Whisper・Stable Diffusion

## 参照

- 姉妹リポ: `/home/hi/projects/geniex-chat-android/`
- サンプル: https://github.com/qualcomm/ai-hub-apps (BSD-3-Clause)
- モデル: https://aihub.qualcomm.com / HuggingFace `qualcomm/*` org
- 検出モデル: https://aihub.qualcomm.com/compute/models/yolox
