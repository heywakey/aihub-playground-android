#!/usr/bin/env bash
# clean → assembleRelease → 署名して dist/aihub-playground-v1.apk を作る。
# 鍵が無ければ release.sh と同じ方式で自動生成する。
set -uo pipefail
BASE="$(cd "$(dirname "$0")/.." && pwd)"
SDK="$BASE/android-sdk"
APP="$BASE/app"
BT="$SDK/build-tools/34.0.0"
KEYS="$BASE/release-keys"
GRV=9.1.0
mkdir -p "$BASE/logs" "$BASE/dist"
LOG="$BASE/logs/rebuild.log"
exec > >(tee -a "$LOG") 2>&1
echo "==== REBUILD START $(date '+%F %T') ===="

JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
[ -x "$JAVA_HOME/bin/java" ] || JAVA_HOME="$BASE/jdk17"
export JAVA_HOME
export ANDROID_HOME="$SDK"
export PATH="$JAVA_HOME/bin:$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$BASE/gradle-$GRV/bin:$PATH"

# keystore 保証(無ければ生成)
mkdir -p "$KEYS"; chmod 700 "$KEYS"
KS="$KEYS/aihub-release.jks"
STOREPASS="AIHub!$(hostname)-2026"
if [ ! -f "$KS" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -v -keystore "$KS" -alias aihub \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$STOREPASS" -keypass "$STOREPASS" \
    -dname "CN=AI Hub Playground, OU=Demo, O=Independent, L=NA, ST=NA, C=JP" 2>&1 | tail -2
  echo "$STOREPASS" > "$KEYS/STOREPASS.txt"; chmod 600 "$KEYS/STOREPASS.txt"
else
  STOREPASS=$(cat "$KEYS/STOREPASS.txt")
fi

echo "sdk.dir=$SDK" > "$APP/local.properties"
cd "$APP"
gradle --no-daemon --console=plain clean assembleRelease 2>&1 | tail -12
RC=${PIPESTATUS[0]}; echo "assembleRelease rc=$RC"
UNSIGNED=$(find "$APP/build/outputs/apk/release" -name "*.apk" | head -1)
echo "unsigned: $UNSIGNED"
"$BT/zipalign" -f -p 4 "$UNSIGNED" "$BASE/dist/aihub-aligned.apk"
OUT="$BASE/dist/aihub-playground-v1.apk"
"$BT/apksigner" sign --ks "$KS" --ks-key-alias aihub \
  --ks-pass pass:"$STOREPASS" --key-pass pass:"$STOREPASS" --out "$OUT" "$BASE/dist/aihub-aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT" 2>&1 | grep -i "certificate DN" | head -1
ls -lh "$OUT"; sha256sum "$OUT"
rm -f "$BASE/dist/aihub-aligned.apk" "$BASE/dist/aihub-aligned.apk.idsig"
echo "==== REBUILD DONE $(date '+%F %T') rc=$RC ===="
