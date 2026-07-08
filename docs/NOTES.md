# AI Hub Playground Android NOTES

## 概要
Qualcomm AI Hub のモデル(視覚系+LLM)をタスクカテゴリ単位で動かせる Android アプリ。
モデルギャラリー → 選択 → カメラライブビューに結果オーバーレイ、という Ultralytics アプリ風 UX。
基準デバイスは Galaxy Z Fold 7。現在フェーズ: **Phase 1 進行中(雛形ビルド成功、次は YOLOX 推論実装)**。

## 重要な決定
- 2026-07-08: setup.sh は sudo 不要方式に変更 — システムに JDK17 が無ければ Temurin をリポ内 jdk17/ に取得、unzip が無ければ python3 で展開。WSL 等 sudo パスワード必須環境でも完結するため
- 2026-07-08: compileSdk 36 / targetSdk 34 — CameraX 1.6.1 が compileSdk 36 以上を要求するため(targetSdk は geniex-chat-android に合わせ 34 のまま)
- 2026-07-08: LiteRT は com.google.ai.edge.litert:litert:1.4.2(1.x 系 = org.tensorflow.lite API 互換)+ qnn-litert-delegate 2.48.0。AI Hub 配布の YOLOX tflite 自体が LiteRT 1.4.4 でビルドされており 1.x 系が正解
- 2026-07-08: モデルカタログは downloadUrl(S3 zip)+ zip 内パス方式 — qualcomm/* の HF リポにはモデル本体が無く、release_assets.json が指す S3 zip(precision/runtime 別)で配布されているため
- 2026-07-08: チャットアプリ(geniex-chat-android)とはリポジトリを分離 — 用途もランタイム構成も違うため。チャットは Phase 3 でこちらに統合予定
- 2026-07-08: 「AI Hub 全モデル対応」ではなく「タスクカテゴリ単位対応」を目標にする — 実装コストはモデル数でなくカテゴリ数(前処理・後処理)で決まるため
- 2026-07-08: モデルカタログは Apache-2.0 / MIT 系のみ。Ultralytics YOLO (AGPL) は不採用、検出は YOLOX を使う — Google Play 配布時の AGPL ソース開示義務を避けるため
- 2026-07-08: モデルは APK 同梱せず実行時に HuggingFace からダウンロード — ライセンス・APK サイズ両面で有利。geniex-chat-android の model_list.json 方式を拡張
- 2026-07-08: ビルド基盤は geniex-chat-android の scripts/ を移植 — クローン→setup.sh→build.sh で完結する可搬構成を踏襲
- 2026-07-08: フェーズ計画 — P1: カメラ+LiteRT/QNN+物体検出(YOLOX)で縦貫通 / P2: 分類・セグメンテーション・超解像・深度を横展開 / P3: GenieX チャット統合+カタログ UI / P4(任意): Whisper・Stable Diffusion

## 作業ログ
- 2026-07-08: Phase 1 立ち上げ。scripts/ 移植(sudo 不要化改良込み)→ app/ Gradle 雛形(compileSdk 36)→ CameraX プレビュー+OverlayView+FPS 計測の骨組み → model_list.json(YOLOX INT8)→ **debug APK ビルド成功(100MB、QNN ネイティブ込み)**。YOLOX の実配布形式を調査: HF `qualcomm/Yolo-X` の release_assets.json → S3 zip(yolox-tflite-w8a8.zip = yolox.tflite 9.4MB + labels.txt + metadata.json)。モデル出力は box decode 済み(boxes[1,8400,4]/scores/class_idx)なので後処理は閾値+NMS のみで済む
- 2026-07-08: プロジェクト立ち上げ。geniex-chat-android 側の Claude Code セッションで企画・設計議論を実施し、CLAUDE.md / docs/DESIGN.md / docs/NOTES.md を作成。git init 済み(初回コミットは未実施)

## 次にやること
- [ ] モデルダウンローダ実装(S3 zip 取得→展開→filesDir 配置。同意 UI + Wi-Fi 警告付き)
- [ ] YOLOX 推論パイプライン実装: ImageProxy→640x640 uint8 前処理 → LiteRT Interpreter(QnnDelegate)→ 閾値+NMS → OverlayView 描画
- [ ] Fold 7 実機で動作確認(NPU 実行になっているかは推論時間とdelegateログで確認、memwatch.sh 併用)
- [ ] ai-hub-apps の Object Detection サンプルの前後処理コードと照合、流用した場合は LICENSE/NOTICE 整備
- [ ] AI Hub 配布 zip(S3)のライセンス条項確認 — HF リポの license タグは "other"(LICENSE は Megvii YOLOX = Apache-2.0 を指す)。配布アセット自体の利用条件を Qualcomm AI Hub の terms で要確認
- [ ] release_assets.json をランタイムで解決する方式の検討(バージョン更新への追従。現状はカタログに S3 URL 直書き)
