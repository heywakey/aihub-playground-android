# AI Hub Playground Android NOTES

## 概要
Qualcomm AI Hub のモデル(視覚系+LLM)をタスクカテゴリ単位で動かせる Android アプリ。
モデルギャラリー → 選択 → カメラライブビューに結果オーバーレイ、という Ultralytics アプリ風 UX。
基準デバイスは Galaxy Z Fold 7。現在フェーズ: **Phase 1 縦貫通 完了(実機で YOLOX を NPU 実行、検出オーバーレイまで動作確認済み)**。

## 重要な決定
- 2026-07-08: **QNN HTP(NPU)実行には AndroidManifest に `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>` が必須**。Android 12+ ではこれが無いと DSP RPC ライブラリを dlopen できず、`Failed to load skel / device_handle error=14001` で HTP 初期化が失敗し CPU に落ちる。宣言後は libQnnHtpV79Skel.so が cdsp ドメインにロードされ全ノードが NPU 実行される
- 2026-07-08: 実機接続は **Tailscale 経由の adb over TCP** で行う(`adb connect <tailscale-ip>:5555`)。WSL2 は USB 直結不可・interop 無効で Windows adb.exe も叩けないが、WSL 内に tailscale0 があり tailnet 直結できる。端末側で `adb tcpip 5555` 実行済み前提。初回接続時は端末で USB デバッグ承認が必要
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
- 2026-07-08: **Phase 1 縦貫通 完了。Galaxy Z Fold 7 実機で YOLOX が動作**。モデルDL(S3 zip→filesDir展開)→ LiteRT+QNN HTP delegate で全317ノードを Hexagon NPU(v79)実行 → letterbox前処理 → dequant → クラス別NMS → CameraX ライブビューにオーバーレイ描画。実測 **30fps / 推論27ms / QNN-HTP(NPU)**、cup/mouse/keyboard 等を正しく検出しボックス位置も一致。ハマりどころ2点: (1) `libcdsprpc.so` の uses-native-library 宣言漏れで HTP 初期化失敗→CPU落ち (2) 出力の scores/class_idx をスケール順で判定していたが class_idx のスケールが 0 のため取り違え→スコア常時0で検出ゼロ。テンソル名でマッピングし class_idx は生値使用で解決。画面ドーズ対策に FLAG_KEEP_SCREEN_ON 追加
- 2026-07-08: Phase 1 立ち上げ。scripts/ 移植(sudo 不要化改良込み)→ app/ Gradle 雛形(compileSdk 36)→ CameraX プレビュー+OverlayView+FPS 計測の骨組み → model_list.json(YOLOX INT8)→ **debug APK ビルド成功(100MB、QNN ネイティブ込み)**。YOLOX の実配布形式を調査: HF `qualcomm/Yolo-X` の release_assets.json → S3 zip(yolox-tflite-w8a8.zip = yolox.tflite 9.4MB + labels.txt + metadata.json)。モデル出力は box decode 済み(boxes[1,8400,4]/scores/class_idx)なので後処理は閾値+NMS のみで済む
- 2026-07-08: プロジェクト立ち上げ。geniex-chat-android 側の Claude Code セッションで企画・設計議論を実施し、CLAUDE.md / docs/DESIGN.md / docs/NOTES.md を作成。git init 済み(初回コミットは未実施)

## 次にやること
- [ ] モデルDL の UX 整備: 同意 UI + Wi-Fi/従量課金警告 + 進捗バー(現状は statusText にテキスト表示のみ、初回起動時に無警告で 9.4MB DL する)
- [ ] 検出のチューニング: score/IoU 閾値の調整、フロントカメラ切替、検出クラスのフィルタ UI
- [ ] AI Hub 配布 zip(S3)のライセンス条項確認 — HF リポの license タグは "other"(LICENSE は Megvii YOLOX = Apache-2.0 を指す)。配布アセット自体の利用条件を Qualcomm AI Hub の terms で要確認
- [ ] release_assets.json をランタイムで解決する方式の検討(バージョン更新への追従。現状はカタログに S3 URL 直書き)
- [ ] Phase 2 横展開: 画像分類・セグメンテーション・超解像・深度(前処理は共通化済み、後処理を追加。モデルカタログに type 別エントリ追加)
- [ ] ai-hub-apps の前後処理コードと照合し流用した箇所は LICENSE/NOTICE 整備(現状は独自実装なので出自明記は未発生)
- [ ] モデルギャラリー UI(カタログから選択して切替。現状は先頭の detection エントリを自動選択)
