#!/usr/bin/env bash
# リリースAPKをビルド→zipalign→署名→検証。
#
# 【最重要】署名鍵は release-keys/aihub-release.jks の「1本」を永久に使い回す。
#   Android は同じ署名鍵の APK しか上書き更新できない。鍵が変わると既存ユーザーは
#   アンインストール→再インストール(データ消失)が必須になる。
#   このため通常実行では鍵を自動生成しない。鍵が無ければ中断する。
#
#   初回だけ:   scripts/release.sh init-key     … 鍵を1回だけ生成する
#   以後:       scripts/release.sh              … 既存の鍵で署名する
#
#   release-keys/ は .gitignore 済み(リポには入らない)。生成後は必ず安全な場所へ
#   バックアップすること。これを失うと二度と更新版を出せない。
set -uo pipefail
BASE="$(cd "$(dirname "$0")/.." && pwd)"
SDK="$BASE/android-sdk"
APP="$BASE/app"
BT="$SDK/build-tools/34.0.0"
KEYS="$BASE/release-keys"
KS="$KEYS/aihub-release.jks"
PASSFILE="$KEYS/STOREPASS.txt"
ALIAS="aihub"
GRV=9.1.0

JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
[ -x "$JAVA_HOME/bin/java" ] || JAVA_HOME="$BASE/jdk17"
export JAVA_HOME
export ANDROID_HOME="$SDK"
export PATH="$JAVA_HOME/bin:$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$BASE/gradle-$GRV/bin:$PATH"

# ----------------------------------------------------------------------------
# init-key モード: 署名鍵を1回だけ生成する。既にあれば何もしない。
# ----------------------------------------------------------------------------
if [ "${1:-}" = "init-key" ]; then
  if [ -f "$KS" ]; then
    echo "既に署名鍵があります: $KS"
    echo "再生成はしません(別の鍵で署名すると更新できなくなるため)。"
    exit 1
  fi
  mkdir -p "$KEYS"; chmod 700 "$KEYS"
  # ランダムな強いパスワードを生成し、鍵と同じディレクトリに保存する。
  # (release-keys/ 一式がバックアップ対象の秘密。gitignore 済み)
  STOREPASS="$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 28)"
  "$JAVA_HOME/bin/keytool" -genkeypair -v \
    -keystore "$KS" -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 12000 \
    -storepass "$STOREPASS" -keypass "$STOREPASS" \
    -dname "CN=AI Hub Playground, OU=Demo, O=Independent, L=NA, ST=NA, C=JP" 2>&1 | tail -3
  echo "$STOREPASS" > "$PASSFILE"; chmod 600 "$PASSFILE"
  chmod 400 "$KS"
  echo
  echo "===================================================================="
  echo " 署名鍵を生成しました: $KS"
  "$JAVA_HOME/bin/keytool" -list -v -keystore "$KS" -storepass "$STOREPASS" 2>/dev/null \
    | grep -E "SHA256:|Valid" | head -3
  echo
  echo " !!! いますぐ release-keys/ 一式を安全な場所にバックアップせよ !!!"
  echo "     (これを失うと更新版APKを二度と出せなくなる)"
  echo "===================================================================="
  exit 0
fi

# ----------------------------------------------------------------------------
# 通常リリース: 鍵は必須。無ければ止める(うっかり別鍵署名の事故を防ぐ)。
# ----------------------------------------------------------------------------
if [ ! -f "$KS" ] || [ ! -f "$PASSFILE" ]; then
  echo "ERROR: 署名鍵がありません: $KS"
  echo
  echo "  初回の場合:        scripts/release.sh init-key"
  echo "  既存鍵がある場合:  バックアップから release-keys/ を復元してから再実行"
  echo
  echo "(debug ビルドで良ければ scripts/build.sh を使うこと)"
  exit 1
fi
STOREPASS=$(cat "$PASSFILE")

mkdir -p "$BASE/logs" "$BASE/dist"
LOG="$BASE/logs/release.log"
exec > >(tee -a "$LOG") 2>&1
echo "==== RELEASE START $(date '+%F %T') ===="

VC=$(grep -oE 'versionCode[[:space:]]+[0-9]+' "$APP/build.gradle" | grep -oE '[0-9]+')
VN=$(grep -oE "versionName[[:space:]]+'[^']+'" "$APP/build.gradle" | grep -oE "'[^']+'" | tr -d "'")
echo "versionCode=$VC versionName=$VN"

echo "---- [1/4] assembleRelease (unsigned) ----"
echo "sdk.dir=$SDK" > "$APP/local.properties"
cd "$APP"
gradle --no-daemon --console=plain assembleRelease 2>&1 | tail -8
RC=${PIPESTATUS[0]}
echo "assembleRelease rc=$RC"
[ "$RC" = "0" ] || { echo "ビルド失敗"; exit 1; }
UNSIGNED=$(find "$APP/build/outputs/apk/release" -name "*.apk" | head -1)
echo "unsigned: $UNSIGNED"; ls -lh "$UNSIGNED"

echo "---- [2/4] zipalign ----"
ALIGNED="$BASE/dist/app-release-aligned.apk"
"$BT/zipalign" -f -p 4 "$UNSIGNED" "$ALIGNED"

echo "---- [3/4] apksigner sign ----"
OUT="$BASE/dist/aihub-playground-v${VN}-${VC}.apk"
"$BT/apksigner" sign \
  --ks "$KS" --ks-key-alias "$ALIAS" \
  --ks-pass pass:"$STOREPASS" --key-pass pass:"$STOREPASS" \
  --out "$OUT" "$ALIGNED"
rm -f "$ALIGNED" "$ALIGNED.idsig"
echo "signed: $OUT"

echo "---- [4/4] verify ----"
"$BT/apksigner" verify --print-certs "$OUT" 2>&1 | head -8
echo "final APK:"; ls -lh "$OUT"; sha256sum "$OUT"
echo "==== RELEASE DONE $(date '+%F %T') ===="
