# AI Hub Playground Android — Claude Code 向けプロジェクト指示

## このプロジェクトは何か

Qualcomm AI Hub のモデル(視覚系+LLM)を **タスクカテゴリ単位** で動かせる
Android アプリ。Ultralytics アプリのような「モデルギャラリー → 選択 →
カメラライブビューに結果オーバーレイ」という UX を目指す。
基準デバイスは **Galaxy Z Fold 7(Snapdragon 8 Elite for Galaxy, RAM 12GB)**。

姉妹プロジェクト `/home/hi/projects/geniex-chat-android/`(GenieX チャットアプリ)から
派生した企画だが、リポジトリは意図的に分離している。チャット機能は Phase 3 で
このアプリにも統合予定。

## セッション開始時に必ずやること

1. `docs/NOTES.md` を読んで現在の状況・次のタスクを把握する
2. 設計判断の背景が必要なら `docs/DESIGN.md` を読む

## 重要な前提・制約

- **ライセンス方針**: Google Play ストア公開を見据え、モデルカタログは
  **Apache-2.0 / MIT 系のみ**。Ultralytics 系 YOLO(v8/v11, AGPL-3.0)は使わない。
  物体検出は **YOLOX(Apache-2.0)** を使う。詳細は `docs/DESIGN.md` の「ライセンス方針」。
- **モデルは APK に同梱しない**。実行時に HuggingFace からダウンロードする方式
  (geniex-chat-android と同じ。ライセンス的にも APK サイズ的にも有利)。
- **ビルド基盤は geniex-chat-android から移植する**:
  `scripts/setup.sh`(JDK17 + Android SDK + Gradle をリポ内に導入)、
  `build.sh` / `release.sh` / `rebuild.sh` / `resetup.sh` の「クローンすれば
  どこでもビルドできる」方式。SDK・Gradle・keystore・build 出力は gitignore。
- 視覚系ランタイムは LiteRT (TFLite) + QNN delegate(Hexagon NPU 実行)、
  LLM は GenieX(`com.qualcomm.qti:geniex-android`)。

## 参照すべき外部資産

- `/home/hi/projects/geniex-chat-android/` — scripts/ 一式、
  `app/src/main/assets/model_list.json` のカタログ方式、GenieX チャット実装、
  Gradle 構成(minSdk 31 / targetSdk 34 / JDK17)の手本
- [qualcomm/ai-hub-apps](https://github.com/qualcomm/ai-hub-apps) —
  Object Detection / Image Classification / Semantic Segmentation /
  Super Resolution 等の Android サンプル(BSD-3-Clause)。前処理・後処理コードを流用する
- [Qualcomm AI Hub](https://aihub.qualcomm.com/) と HuggingFace の `qualcomm/*` org —
  コンパイル済み .tflite/.onnx モデルの配布元

## 記録の運用

- 作業の区切りで `docs/NOTES.md` の「作業ログ」「次にやること」を更新する
- 設計判断をしたら「重要な決定」に日付付きで追記する(理由も書く)
- ai-hub-apps 由来のコードを取り込んだら、geniex-chat-android と同様に
  LICENSE / NOTICE ファイルで出自を明記する
