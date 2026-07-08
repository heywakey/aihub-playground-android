# AI Hub Playground (Android)

Qualcomm AI Hub のモデル(視覚系+LLM)をタスクカテゴリ単位で動かす Android アプリ。
モデルギャラリーから選んで、カメラライブビューに結果をオーバーレイ表示する。
基準デバイス: Galaxy Z Fold 7。

- 設計: [docs/DESIGN.md](docs/DESIGN.md)
- 現状と TODO: [docs/NOTES.md](docs/NOTES.md)
- 姉妹プロジェクト: `../geniex-chat-android`(GenieX チャット。ビルド基盤の移植元)

## Build

クローン後、リポ内にツールチェーンを導入してビルドする(sudo 不要。
システムに JDK17 が無ければ Temurin をリポ内に取得する):

```bash
./scripts/setup.sh    # JDK17 + Android SDK + Gradle 9.1.0 をリポ配下に導入(初回のみ)
./scripts/build.sh    # debug APK → app/build/outputs/apk/debug/app-debug.apk
./scripts/release.sh  # 署名付き release APK → dist/(鍵は無ければ自動生成)
```

- `scripts/rebuild.sh` — clean からの release 再ビルド
- `scripts/resetup.sh` — ツールチェーンを消して再導入(ビルド環境リセット)
- `scripts/memwatch.sh` — 実機メモリ監視(adb 経由、デバッグ用)

## Status

Phase 1 進行中。scripts / Gradle 雛形 / CameraX プレビュー+オーバーレイ骨組み /
モデルカタログ(YOLOX INT8)まで完了。次: LiteRT + QNN delegate での YOLOX 推論実装。
