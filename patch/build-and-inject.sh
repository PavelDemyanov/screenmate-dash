#!/usr/bin/env bash
# Rebuild the patched STOCK Screenmate APK with the SmdashPanel settings-block injection.
#
# The helper class is compiled to its own classes3.dex and appended to the apktool-built APK
# (STORED, uncompressed). No baksmali is needed — apktool's bundled baksmali is shaded and unusable,
# and ART loads every classesN.dex, so the one-line `invoke-static ...SmdashPanel;->inject` hook in
# SectionDisplay.smali (classes2.dex) resolves the class from classes3.dex at runtime.
#
# Prereq: the smali hook must already be present in $DEC (inserted at the START of
# onViewModelReady()V, because p0 is reassigned before the method returns):
#     invoke-static {p0}, Lapp/smdash/inject/SmdashPanel;->inject(Landroid/view/View;)V
#
# Usage: build-and-inject.sh [DEC_DIR] [OUT_APK]
set -euo pipefail

DEC="${1:-/Volumes/smcs/dec}"                       # case-sensitive apktool -r decompile tree
OUT="${2:-/Volumes/smcs/built.apk}"
HERE="$(cd "$(dirname "$0")" && pwd)"

JAVA_HOME_17=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
JAVA=/opt/homebrew/opt/openjdk/bin/java                       # any real JRE (d8 wrapper picks /usr/bin stub otherwise)
JAVAC=/opt/homebrew/opt/openjdk/bin/javac
SDK=/opt/homebrew/share/android-commandlinetools
AJ="$SDK/platforms/android-34/android.jar"
D8JAR="$SDK/build-tools/34.0.0/lib/d8.jar"

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/classes" "$WORK/dex"   # d8 requires the output dir to already exist
echo "==> javac SmdashPanel.java"
"$JAVAC" -source 17 -target 17 -cp "$AJ" -d "$WORK/classes" "$HERE/SmdashPanel.java" 2>&1 | grep -v "bootstrap class path" || true
echo "==> d8 -> classes3.dex"
"$JAVA" -cp "$D8JAR" com.android.tools.r8.D8 --min-api 29 --output "$WORK/dex" $(find "$WORK/classes" -name '*.class')
cp "$WORK/dex/classes.dex" "$WORK/classes3.dex"

echo "==> apktool b $DEC"
JAVA_HOME="$JAVA_HOME_17" apktool b "$DEC" -o "$OUT" >/dev/null
echo "==> append classes3.dex (stored)"
( cd "$WORK" && zip -X -0 "$OUT" classes3.dex >/dev/null )

echo "==> verify"
RES=$(unzip -l "$OUT" 'res/*' | grep -c 'res/')
echo "   res files : $RES (want 1003)"
echo "   dex       : $(unzip -l "$OUT" | grep -cE 'classes.*\.dex') (want 3)"
echo "   md5       : $(md5 -q "$OUT")  <-- put in app.smdash Patcher.kt PATCHED_MD5"
[ "$RES" = "1003" ] || { echo "!! res count mismatch — case-sensitivity loss?"; exit 1; }
echo "OK: $OUT"
