#!/usr/bin/env bash
# デバッグAPKをビルド。署名鍵は不要(Android のデバッグ鍵で自動署名)。
set -uo pipefail
BASE="$(cd "$(dirname "$0")/.." && pwd)"
SDK="$BASE/android-sdk"
APP="$BASE/app"
GRV=9.1.0
mkdir -p "$BASE/logs"
LOG="$BASE/logs/build.log"
exec > >(tee -a "$LOG") 2>&1
echo "==== BUILD START $(date '+%F %T') ===="

JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
[ -x "$JAVA_HOME/bin/java" ] || JAVA_HOME="$BASE/jdk17"
export JAVA_HOME
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$JAVA_HOME/bin:$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$BASE/gradle-$GRV/bin:$PATH"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1

echo "sdk.dir=$SDK" > "$APP/local.properties"
cd "$APP"
gradle --no-daemon --console=plain assembleDebug 2>&1 | tail -40
RC=${PIPESTATUS[0]}
echo "assembleDebug rc=$RC"
echo "---- APK output ----"
find "$APP/build/outputs/apk" -name "*.apk" 2>/dev/null -exec ls -lh {} \;
echo "==== BUILD DONE $(date '+%F %T') rc=$RC ===="
