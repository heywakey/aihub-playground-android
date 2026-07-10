# 配布(自己配布 / Google Play 非経由)

Google Play を使わず、自分のサイトのダウンロードリンクで APK を配る運用のメモ。

## リリース手順(毎回)

1. `app/build.gradle` の `versionCode` を +1、`versionName` を更新する
   （versionCode を上げないと端末が「新しい版」と認識せず更新できない）
2. `scripts/release.sh` を実行 → `dist/aihub-playground-v<name>-<code>.apk` が出る
   （署名鍵は `release-keys/aihub-release.jks` の1本を必ず使い回す。無ければ中断する）
3. その APK を配布ホストに上げる（推奨: **GitHub Releases**。無料CDN・帯域無料・版管理）
4. この `distribution/latest.json` を新しい版に合わせて更新し、**アプリが参照する URL**
   （下記）に反映する。アプリは起動時にこれを読んで更新を促す

## latest.json

アプリの `UpdateChecker.LATEST_JSON_URL` が指す JSON。フィールド:

| キー | 意味 |
|---|---|
| `versionCode` | 最新APKの versionCode（インストール済みと数値比較する） |
| `versionName` | 表示用バージョン名 |
| `apkUrl` | APK の直リンク（ブラウザで開いてDLさせる） |
| `notes` | 変更点（ダイアログにそのまま表示。改行は `\n`） |
| `minSupportedVersionCode` | これ未満は「強制更新（後で不可）」。通常は 0 |

`UpdateChecker.LATEST_JSON_URL` は現在 GitHub の raw を指す想定:
`https://raw.githubusercontent.com/<user>/<repo>/main/distribution/latest.json`
自分のサイトで配るなら、そのサイト上の JSON URL に差し替える（HTTPS 必須）。

## サイト側の注意

- **HTTPS 必須**。APK は `Content-Type: application/vnd.android.package-archive` で配る
  （GitHub Releases は自動で満たす）
- 初回インストール時、ユーザーは「提供元不明アプリの許可」が必要。手順を1行添える
- Play Protect が「未確認の開発者」警告を出すのはサイドロードの宿命（後でPlay登録で解消）

## 署名鍵(最重要)

- `release-keys/aihub-release.jks` の1本を永久に使い回す。変わると既存ユーザーは
  再インストール（データ消失）必須になる
- `release-keys/` は gitignore 済み。**必ず別の安全な場所へバックアップする**
- 正規鍵 SHA-256:
  `3A:9F:8F:BB:30:80:7B:49:83:40:E5:EC:5B:34:91:2A:05:FD:97:4F:BC:BD:B6:95:7F:39:00:F7:34:FC:4B:22`
