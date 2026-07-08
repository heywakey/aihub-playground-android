#!/usr/bin/env bash
# リリースAPKをビルド→zipalign→署名→検証。
# 署名鍵(release-keys/)が無ければその場で自動生成する(パスワードは hostname 由来)。
# → 別環境では鍵の持ち込み不要。ただしマシンごとに鍵が異なるため、別マシンで
#   作った APK 同士は上書き更新できない(新規インストール運用が前提)。
set -uo pipefail
BASE="$(cd "$(dirname "$0")/.." && pwd)"
SDK="$BASE/android-sdk"
APP="$BASE/app"
BT="$SDK/build-tools/34.0.0"
KEYS="$BASE/release-keys"
GRV=9.1.0
mkdir -p "$BASE/logs" "$BASE/dist"
LOG="$BASE/logs/release.log"
exec > >(tee -a "$LOG") 2>&1
echo "==== RELEASE START $(date '+%F %T') ===="

JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
[ -x "$JAVA_HOME/bin/java" ] || JAVA_HOME="$BASE/jdk17"
export JAVA_HOME
export ANDROID_HOME="$SDK"
export PATH="$JAVA_HOME/bin:$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$BASE/gradle-$GRV/bin:$PATH"

echo "---- [1/5] keystore (無ければ自動生成) ----"
mkdir -p "$KEYS"; chmod 700 "$KEYS"
KS="$KEYS/aihub-release.jks"
STOREPASS="AIHub!$(hostname)-2026"
if [ ! -f "$KS" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -v \
    -keystore "$KS" -alias aihub \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$STOREPASS" -keypass "$STOREPASS" \
    -dname "CN=AI Hub Playground, OU=Demo, O=Independent, L=NA, ST=NA, C=JP" 2>&1 | tail -3
  echo "$STOREPASS" > "$KEYS/STOREPASS.txt"; chmod 600 "$KEYS/STOREPASS.txt"
  echo "keystore created: $KS"
else
  STOREPASS=$(cat "$KEYS/STOREPASS.txt")
  echo "keystore exists"
fi

echo "---- [2/5] assembleRelease (unsigned) ----"
echo "sdk.dir=$SDK" > "$APP/local.properties"
cd "$APP"
gradle --no-daemon --console=plain assembleRelease 2>&1 | tail -8
RC=${PIPESTATUS[0]}
echo "assembleRelease rc=$RC"
UNSIGNED=$(find "$APP/build/outputs/apk/release" -name "*.apk" | head -1)
echo "unsigned: $UNSIGNED"; ls -lh "$UNSIGNED"

echo "---- [3/5] zipalign ----"
ALIGNED="$BASE/dist/app-release-aligned.apk"
"$BT/zipalign" -f -p 4 "$UNSIGNED" "$ALIGNED"

echo "---- [4/5] apksigner sign ----"
OUT="$BASE/dist/aihub-playground-release.apk"
"$BT/apksigner" sign \
  --ks "$KS" --ks-key-alias aihub \
  --ks-pass pass:"$STOREPASS" --key-pass pass:"$STOREPASS" \
  --out "$OUT" "$ALIGNED"
rm -f "$ALIGNED" "$ALIGNED.idsig"
echo "signed: $OUT"

echo "---- [5/5] verify ----"
"$BT/apksigner" verify --print-certs "$OUT" 2>&1 | head -8
echo "final APK:"; ls -lh "$OUT"; sha256sum "$OUT"
echo "==== RELEASE DONE $(date '+%F %T') ===="
