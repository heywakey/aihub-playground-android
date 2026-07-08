#!/usr/bin/env bash
# 接続中の Android 端末のメモリ推移を logs/memwatch.txt に記録する(任意のデバッグ用)。
# DEVICE は実機のシリアル。環境変数 DEVICE で上書き可: DEVICE=XXXX ./scripts/memwatch.sh
set -uo pipefail
BASE="$(cd "$(dirname "$0")/.." && pwd)"
DEVICE="${DEVICE:-$(adb devices | awk 'NR==2{print $1}')}"
mkdir -p "$BASE/logs"
OUT="$BASE/logs/memwatch.txt"
: > "$OUT"
echo "device=$DEVICE -> $OUT"
for i in $(seq 1 40); do
  TS=$(date '+%H:%M:%S')
  AV=$(adb -s "$DEVICE" shell cat /proc/meminfo 2>/dev/null | tr -d '\r' | awk '/MemAvailable/{print $2}')
  SF=$(adb -s "$DEVICE" shell cat /proc/meminfo 2>/dev/null | tr -d '\r' | awk '/SwapFree/{print $2}')
  echo "$TS MemAvail=${AV}kB SwapFree=${SF}kB" >> "$OUT"
  sleep 2
done
