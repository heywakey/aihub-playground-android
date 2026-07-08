# AI Hub Playground Android NOTES

## 概要
Qualcomm AI Hub のモデル(視覚系+LLM)をタスクカテゴリ単位で動かせる Android アプリ。
モデルギャラリー → 選択 → カメラライブビューに結果オーバーレイ、という Ultralytics アプリ風 UX。
基準デバイスは Galaxy Z Fold 7。現在フェーズ: **Phase 2 完了(5カテゴリを実機で NPU 実行、モデルギャラリー+DL UX まで動作確認済み)**。

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
- 2026-07-08: **Phase 2.1: 超解像を bilinear vs SR 左右比較に + Transformer系/代替モデルを追加(全11モデル)、実機検証済み**。追加分の実測(全て QNN-HTP NPU): ViT(Transformer分類)30.4fps/20ms、SegFormer(Transformerセグ, ADE20K)24.7fps/41ms、**Depth-Anything V2(Transformer深度, float32)8.9fps/139ms** — float モデルも HTP に fp16 で載り CPU 落ちせず動作(MiDaS 17ms より重いが明瞭)、Real-ESRGAN x4(超解像GAN)25.9fps/36ms。超解像は同一クロップの bilinear ×4 と SR ×4 を左右比較インセット表示にして効果を可視化。汎用化2点: セグは出力 [1,H,W,C] ロジットの argmax に対応(DeepLabのクラスID直と両対応)、LiteRtEngine/各Taskは入力/出力 float32 に対応。UI自動検証は座標ずれ対策で uiautomator のテキスト指定タップに切替(部分一致で「モデルをダウンロード」を掴む罠→ボタンは完全一致で特定)。ライセンス注記: 物体検出は Apache が YOLOX のみ(他YOLOはAGPL)。ADE20K ラベル未同梱で SegFormer 凡例は classN 表示
- 2026-07-08: **Phase 2 完了。5カテゴリすべて Fold 7 実機で NPU 実行を確認**。VisionTask/VisionResult でタスク抽象化し、LiteRtEngine(QNN HTP/CPU 初期化 + 入力再量子化 LUT)を共通化。実測(全て QNN-HTP NPU): 物体検出 YOLOX 30fps/27ms、画像分類 MobileNet-v2 29.8fps/27ms(mouse/keyboard を正しく分類)、セグメンテーション DeepLabV3+ 28.7fps/18ms(tvmonitor をマスク、VOC21クラス)、超解像 XLSR 29fps/21ms(中央128px→512pxインセット)、深度 MiDaS 30fps/17ms(相対深度を Turbo カラーマップ)。モデルギャラリー(カタログ一覧・DL状態・同意ダイアログ・進捗バー)も動作。ハマり: MainActivity を exported=false にしたら adb 直接起動が Permission Denial → 検証は adb input tap でギャラリー操作を駆動(本来のユーザーフロー検証にもなった)。入力は全モデル io_type=image/value_range[0,1] なので入力テンソルの scale/zp で再量子化する汎用 LUT に統一(Midas は scale≠1/255)
- 2026-07-08: **Phase 1 縦貫通 完了。Galaxy Z Fold 7 実機で YOLOX が動作**。モデルDL(S3 zip→filesDir展開)→ LiteRT+QNN HTP delegate で全317ノードを Hexagon NPU(v79)実行 → letterbox前処理 → dequant → クラス別NMS → CameraX ライブビューにオーバーレイ描画。実測 **30fps / 推論27ms / QNN-HTP(NPU)**、cup/mouse/keyboard 等を正しく検出しボックス位置も一致。ハマりどころ2点: (1) `libcdsprpc.so` の uses-native-library 宣言漏れで HTP 初期化失敗→CPU落ち (2) 出力の scores/class_idx をスケール順で判定していたが class_idx のスケールが 0 のため取り違え→スコア常時0で検出ゼロ。テンソル名でマッピングし class_idx は生値使用で解決。画面ドーズ対策に FLAG_KEEP_SCREEN_ON 追加
- 2026-07-08: Phase 1 立ち上げ。scripts/ 移植(sudo 不要化改良込み)→ app/ Gradle 雛形(compileSdk 36)→ CameraX プレビュー+OverlayView+FPS 計測の骨組み → model_list.json(YOLOX INT8)→ **debug APK ビルド成功(100MB、QNN ネイティブ込み)**。YOLOX の実配布形式を調査: HF `qualcomm/Yolo-X` の release_assets.json → S3 zip(yolox-tflite-w8a8.zip = yolox.tflite 9.4MB + labels.txt + metadata.json)。モデル出力は box decode 済み(boxes[1,8400,4]/scores/class_idx)なので後処理は閾値+NMS のみで済む
- 2026-07-08: プロジェクト立ち上げ。geniex-chat-android 側の Claude Code セッションで企画・設計議論を実施し、CLAUDE.md / docs/DESIGN.md / docs/NOTES.md を作成。git init 済み(初回コミットは未実施)

## 次にやること
- [ ] SegFormer/ADE20K のラベルを assets に同梱し凡例に名前表示(現状 classN)
- [ ] ギャラリーをカテゴリ別セクション表示に(現状フラットリスト11件)
- [ ] フロントカメラ切替、検出の score/IoU 閾値 UI、分類/セグの表示チューニング
- [ ] 超解像・深度は現状フレーム全体を歪めてリサイズ(seg/depth)/中央固定クロップ(superres)。ピンチズームや領域選択の UX 検討
- [ ] AI Hub 配布 zip(S3)のライセンス条項確認 — HF リポの license タグは "other"。配布アセット自体の利用条件を Qualcomm AI Hub の terms で要確認(YOLOX/MobileNet/DeepLab/XLSR=Apache-2.0, MiDaS=MIT で設定済みだが要裏取り)
- [ ] release_assets.json をランタイムで解決する方式の検討(バージョン更新への追従。現状はカタログに S3 URL 直書き)
- [ ] Phase 3: GenieX チャット統合(geniex-chat-android から移植)、カタログに chat 型を追加
- [ ] ai-hub-apps の前後処理コードと照合し流用した箇所は LICENSE/NOTICE 整備(現状は独自実装なので出自明記は未発生)
- [ ] モデル追加を容易にする(現状カタログ追記だけで済むが、出力テンソル名の想定が外れるモデルは個別対応が要る)
