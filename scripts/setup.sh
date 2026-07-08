#!/usr/bin/env bash
# 新環境セットアップ: JDK17 + Android SDK + Gradle をこのリポ配下に用意する。
# sudo 不要。JDK はシステムに JDK17 があればそれを使い、無ければ Temurin を
# リポ内 jdk17/ にダウンロードする。unzip が無い環境では python3 で展開する。
# アプリ本体(app/)はリポに含まれるので clone は不要。ツールチェーンは gitignore。
set -uo pipefail
BASE="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$BASE/logs"
LOG="$BASE/logs/setup.log"
exec > >(tee -a "$LOG") 2>&1
echo "==== SETUP START $(date '+%F %T') ===="

# unzip が無い環境向けフォールバック(実行権限も復元する)
unzip_to() { # $1=zip $2=dest
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$1" -d "$2"
  else
    python3 - "$1" "$2" <<'EOF'
import sys, zipfile, os
zf = zipfile.ZipFile(sys.argv[1])
for info in zf.infolist():
    path = zf.extract(info, sys.argv[2])
    perm = info.external_attr >> 16
    if perm:
        os.chmod(path, perm)
EOF
  fi
}

echo "---- [1/3] JDK17 ----"
SYS_JDK=/usr/lib/jvm/java-17-openjdk-amd64
if [ -x "$SYS_JDK/bin/java" ]; then
  JAVA_HOME="$SYS_JDK"
else
  JAVA_HOME="$BASE/jdk17"
  if [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "system JDK17 なし → Temurin 17 をリポ内にダウンロード"
    curl -fsSLo "$BASE/jdk17.tar.gz" \
      "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
    rm -rf "$BASE/jdk17.tmp" && mkdir -p "$BASE/jdk17.tmp"
    tar -xzf "$BASE/jdk17.tar.gz" -C "$BASE/jdk17.tmp"
    mv "$BASE/jdk17.tmp"/jdk-17* "$JAVA_HOME"
    rm -rf "$BASE/jdk17.tmp" "$BASE/jdk17.tar.gz"
  fi
fi
export JAVA_HOME
"$JAVA_HOME/bin/java" -version 2>&1 | head -1

echo "---- [2/3] Android SDK (cmdline-tools + packages) ----"
SDK="$BASE/android-sdk"
mkdir -p "$SDK/cmdline-tools"
if [ ! -d "$SDK/cmdline-tools/latest" ]; then
  cd "$BASE"
  CLT=commandlinetools-linux-11076708_latest.zip
  curl -fsSLo "$CLT" "https://dl.google.com/android/repository/$CLT"
  rm -rf "$SDK/cmdline-tools/tmp" && mkdir -p "$SDK/cmdline-tools/tmp"
  unzip_to "$CLT" "$SDK/cmdline-tools/tmp"
  mv "$SDK/cmdline-tools/tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$SDK/cmdline-tools/tmp" "$CLT"
fi
export ANDROID_HOME="$SDK"
export PATH="$JAVA_HOME/bin:$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"
yes | sdkmanager --sdk_root="$SDK" --licenses >/dev/null 2>&1
sdkmanager --sdk_root="$SDK" "platform-tools" "platforms;android-36" "build-tools;34.0.0" 2>&1 | tail -3

echo "---- [3/3] Gradle 9.1.0 ----"
GRV=9.1.0
if [ ! -x "$BASE/gradle-$GRV/bin/gradle" ]; then
  cd "$BASE"
  curl -fsSLo gradle.zip "https://services.gradle.org/distributions/gradle-$GRV-bin.zip"
  unzip_to gradle.zip "$BASE"
  rm gradle.zip
fi
echo "==== SETUP DONE $(date '+%F %T') ===="
du -sh "$BASE" 2>/dev/null
