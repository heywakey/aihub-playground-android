#!/usr/bin/env bash
# ツールチェーンを作り直す(android-sdk と gradle を消して setup.sh を再実行)。
# ビルドがおかしくなった時のリセット用。アプリ(app/)には触らない。
set -uo pipefail
BASE="$(cd "$(dirname "$0")/.." && pwd)"
echo "==== RESETUP: android-sdk / gradle-9.1.0 を削除して再構築 ===="
rm -rf "$BASE/android-sdk" "$BASE/gradle-9.1.0"
exec "$(dirname "$0")/setup.sh"
