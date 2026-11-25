#!/bin/bash
# Manual release-APK pipeline for obsidian-editor-shell (no Gradle).
#
# Why: in this sandbox the runtime intercepts Java processes' TCP
# ("muse: Other TCP connections is turned off for this assistant"), so the
# Gradle client can never reach its forked daemon and every Gradle build
# fails with "Could not receive a message from the daemon". This script
# builds the same release APK with SDK tools only, all in-process/local:
#   aapt2 (resources+manifest) -> javac (R) -> kotlinc embeddable (app code)
#   -> d8 (dex) -> zipalign -> apksigner (keystore/oe-release.jks, alias oe)
#
# Prereqs:
#   - app/src/main/assets/editor.html holds the editor build to ship.
#   - Dependencies in /tmp/oe-deps: python3 manual-build/fetch-deps.py \
#       androidx.core:core-ktx:1.13.1 androidx.activity:activity-ktx:1.9.2 \
#       org.jetbrains.kotlin:kotlin-stdlib:2.0.20 \
#       org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.20
# Output: ~/workspace/your_files/obsidian-editor-shell.apk
set -euo pipefail
PROJ=~/workspace/obsidian-editor-shell
TOOLS=~/workspace/android-tools
export JAVA_HOME=$TOOLS/jdk17
export PATH=$JAVA_HOME/bin:$PATH
BT=$TOOLS/sdk/build-tools/34.0.0
AJ=$TOOLS/sdk/platforms/android-34/android.jar
B=/tmp/oe-build
DEPS=/tmp/oe-deps
rm -rf "$B" /tmp/oe-aar && mkdir -p "$B" /tmp/oe-aar

# --- extract AARs ---
(cd "$DEPS" && find . -name "*.aar" | while read -r aar; do
  name=$(basename "$aar" .aar); mkdir -p "/tmp/oe-aar/$name"
  (cd "/tmp/oe-aar/$name" && unzip -oq "$DEPS/${aar#./}")
done)

# --- processed manifest (applicationId + package + uses-sdk, as AGP would) ---
python3 - "$PROJ" "$B" <<'EOF'
import sys
proj, b = sys.argv[1], sys.argv[2]
src = open(proj + '/app/src/main/AndroidManifest.xml').read()
src = src.replace('${applicationId}', 'app.inboxeditor.shell')
src = src.replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android"',
                  '<manifest package="app.inboxeditor.shell" xmlns:android="http://schemas.android.com/apk/res/android"')
src = src.replace('xmlns:tools="http://schemas.android.com/tools">',
                  'xmlns:tools="http://schemas.android.com/tools">\n    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34" />', 1)
open(b + '/AndroidManifest.xml', 'w').write(src)
EOF

# --- aapt2 compile + link ---
$BT/aapt2 compile --dir "$PROJ/app/src/main/res" -o "$B/res-app.zip"
i=0
for d in /tmp/oe-aar/*/; do
  if [ -d "$d/res" ] && [ -n "$(find "$d/res" -type f 2>/dev/null | head -1)" ]; then
    i=$((i+1)); $BT/aapt2 compile --dir "$d/res" -o "$B/res-lib$i.zip"
  fi
done
PKGS=androidx.activity:androidx.activity.ktx:androidx.annotation.experimental:androidx.core:androidx.core.ktx:androidx.arch.core:androidx.lifecycle.livedata.core:androidx.lifecycle.runtime:androidx.lifecycle.ktx:androidx.lifecycle.viewmodel:androidx.lifecycle.viewmodel.ktx:androidx.lifecycle.viewmodel.savedstate:androidx.profileinstaller:androidx.savedstate:androidx.savedstate.ktx:androidx.startup
$BT/aapt2 link -o "$B/base.apk" -I "$AJ" --manifest "$B/AndroidManifest.xml" --java "$B/gen" \
  --version-code 54 --version-name 1.53 --extra-packages "$PKGS" \
  -A "$PROJ/app/src/main/assets" --auto-add-overlay "$B"/res-lib*.zip "$B/res-app.zip"

# --- compile R classes ---
mkdir -p "$B/classes-r"
(cd "$B" && find gen -name "R.java" > r-sources.txt && javac -cp "$AJ" -d classes-r @r-sources.txt)

# --- library classpath (compile + dex inputs; compiler-only jars excluded) ---
CP=""
for j in /tmp/oe-aar/*/classes.jar; do CP="$CP:$j"; done
for j in $(find "$DEPS" -name "*.jar" ! -name "*compiler-embeddable*" ! -name "*daemon-embeddable*" \
    ! -name "kotlin-reflect-*" ! -name "*script-runtime*" ! -name "trove4j-*" \
    ! -name "kotlin-stdlib-common-*" ! -name "kotlin-stdlib-jdk7-*" ! -name "kotlin-stdlib-jdk8-*"); do CP="$CP:$j"; done

# --- kotlinc (in-process compiler; no daemon) ---
COMPILER=$(find "$DEPS" -name "kotlin-compiler-embeddable-2.0.20.jar")
TROVE=$(find "$DEPS" -name "trove4j-*.jar")
STDLIB=$(find "$DEPS" -name "kotlin-stdlib-2.0.20.jar")
COROUT=$(find "$DEPS" -name "kotlinx-coroutines-core-jvm-*.jar")
SCRIPTJ=$(find "$DEPS" -name "kotlin-script-runtime-*.jar")
DAEMONJ=$(find "$DEPS" -name "kotlin-daemon-embeddable-*.jar")
ANNOT=$(find "$DEPS" -path "*org/jetbrains/annotations*" -name "*.jar")
java -Xmx1g -cp "$COMPILER:$TROVE:$STDLIB:$COROUT:$SCRIPTJ:$DAEMONJ:$ANNOT" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$AJ:$B/classes-r$CP" -d "$B/classes-app" -jvm-target 17 -no-stdlib -no-reflect \
  "$PROJ/app/src/main/java/app/inboxeditor/shell/ShellApp.kt" \
  "$PROJ/app/src/main/java/app/inboxeditor/shell/EditorActivity.kt" \
  "$PROJ/app/src/main/java/app/inboxeditor/shell/MainActivity.kt" \
  "$PROJ/app/src/main/java/app/inboxeditor/shell/QuickActivity.kt" \
  "$PROJ/app/src/main/java/app/inboxeditor/shell/NativeBridge.kt" \
  "$PROJ/app/src/main/java/app/inboxeditor/shell/EditorTileService.kt"

# --- d8 (inputs must be jars; output dir must exist) ---
rm -f "$B/classes-app/META-INF/main.kotlin_module"
mkdir -p "$B/dex"
(cd "$B" && jar cf app-classes.jar -C classes-app . && jar cf r-classes.jar -C classes-r .)
INPUTS="$B/app-classes.jar $B/r-classes.jar"
for j in $(echo "$CP" | tr ':' ' '); do [ -n "$j" ] && INPUTS="$INPUTS $j"; done
$BT/d8 --min-api 26 --lib "$AJ" --output "$B/dex" $INPUTS

# --- assemble, align, sign (password via env: so apksigner reads it twice) ---
cp "$B/base.apk" "$B/app-unsigned.apk"
python3 -c "
import zipfile
with zipfile.ZipFile('$B/app-unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
    z.write('$B/dex/classes.dex','classes.dex')
"
$BT/zipalign -f -p 4 "$B/app-unsigned.apk" "$B/app-aligned.apk"
OE_PASS="$(cat "$PROJ/keystore/keystore-password.txt")" $BT/apksigner sign \
  --ks "$PROJ/keystore/oe-release.jks" --ks-pass env:OE_PASS --key-pass env:OE_PASS \
  --ks-key-alias oe --out "$B/app-release.apk" "$B/app-aligned.apk"
$BT/apksigner verify --verbose "$B/app-release.apk"
cp "$B/app-release.apk" ~/workspace/your_files/obsidian-editor-shell-1.53.apk
echo "DONE: ~/workspace/your_files/obsidian-editor-shell-1.53.apk"
