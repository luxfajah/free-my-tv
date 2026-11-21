#!/usr/bin/env bash
# Reproducible APK build for HY300Remote-Android (no Gradle needed)
# Pipeline: aapt2 -> javac -> d8 -> zip -> zipalign -> apksigner -> verify
set -euo pipefail
cd "$(dirname "$0")"

# --- Locate Android SDK
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools \
           /usr/local/share/android-commandlinetools /usr/lib/android-sdk; do
    if [ -d "$d/build-tools" ]; then SDK="$d"; break; fi
  done
fi
[ -n "$SDK" ] && [ -d "$SDK/build-tools" ] || { echo "ERROR: Android SDK not found." >&2; exit 1; }

BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
[ -f "${AJ:-}" ] || { echo "ERROR: android.jar not found in $SDK/platforms." >&2; exit 1; }

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
command -v "$JAVAC" >/dev/null 2>&1 || { echo "ERROR: javac not found." >&2; exit 1; }

echo "Using build-tools: $BT"
echo "Using platform:    $AJ"

# --- Clean build dir
rm -rf build
mkdir -p build/classes build/dex build/apk

# --- Compile resources & package manifest/assets with aapt2
RES_ARGS=()
if [ -d res ]; then
  "$BT/aapt2" compile --dir res -o build/res.zip
  RES_ARGS=(build/res.zip)
fi

"$BT/aapt2" link \
  -o build/apk/unsigned.apk \
  -I "$AJ" \
  --manifest AndroidManifest.xml \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  -A assets \
  "${RES_ARGS[@]}"

# --- Compile Java sources (Java 8 bytecode for universal Android compatibility)
JAVA_FILES=$(find src -name '*.java')
"$JAVAC" \
  -Xlint:-options \
  -source 8 -target 8 \
  -bootclasspath "$AJ:$BT/core-lambda-stubs.jar" \
  -classpath "$AJ" \
  -encoding UTF-8 \
  -d build/classes \
  $JAVA_FILES

# --- Dex with d8
CLASS_FILES=$(find build/classes -name '*.class')
"$BT/d8" \
  --release \
  --min-api 24 \
  --lib "$AJ" \
  --output build/dex \
  $CLASS_FILES

# --- Inject classes.dex into APK
(cd build/dex && zip -qj ../apk/unsigned.apk classes.dex)

# --- Zipalign
"$BT/zipalign" -f -p 4 build/apk/unsigned.apk build/apk/aligned.apk

# --- Sign APK
KEYSTORE="release.keystore"
KEY_ALIAS="hy300remote"
KEY_PASS="android"

if [ ! -f "$KEYSTORE" ]; then
  "$KEYTOOL" -genkeypair \
    -keystore "$KEYSTORE" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 20000 \
    -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
    -dname "CN=HY300 Remote, O=HY300, C=BR"
fi

"$BT/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KEY_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --out HY300Remote.apk \
  build/apk/aligned.apk

# --- Verify signature
"$BT/apksigner" verify --verbose HY300Remote.apk

echo ""
echo "SUCCESS: Built & verified $(pwd)/HY300Remote.apk ($(du -h HY300Remote.apk | cut -f1))"
