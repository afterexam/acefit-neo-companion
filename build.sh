#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
BT_VER="${BUILD_TOOLS_VERSION:-$(ls -1 "$SDK/build-tools" | sort -V | tail -n 1)}"
PLAT_VER="${COMPILE_SDK:-android-35}"
if [ ! -f "$SDK/platforms/$PLAT_VER/android.jar" ]; then
  PLAT_VER="$(ls -1 "$SDK/platforms" | sort -V | tail -n 1)"
fi

BT="$SDK/build-tools/$BT_VER"
PLAT="$SDK/platforms/$PLAT_VER/android.jar"

rm -rf "$PROJECT_DIR/build/apk"
mkdir -p "$PROJECT_DIR/build/apk/classes" "$PROJECT_DIR/build/apk/dex" "$PROJECT_DIR/build/apk/gen"

"$BT/aapt2" compile --dir "$PROJECT_DIR/res" -o "$PROJECT_DIR/build/apk/res.zip"
"$BT/aapt2" link -o "$PROJECT_DIR/build/apk/base.apk" \
  --manifest "$PROJECT_DIR/AndroidManifest.xml" \
  -I "$PLAT" \
  --java "$PROJECT_DIR/build/apk/gen" \
  "$PROJECT_DIR/build/apk/res.zip"

find "$PROJECT_DIR/src" "$PROJECT_DIR/build/apk/gen" -name '*.java' > "$PROJECT_DIR/build/apk/sources.txt"
javac -source 11 -target 11 -cp "$PLAT" -d "$PROJECT_DIR/build/apk/classes" @"$PROJECT_DIR/build/apk/sources.txt"
"$BT/d8" --min-api 30 --lib "$PLAT" --output "$PROJECT_DIR/build/apk/dex" $(find "$PROJECT_DIR/build/apk/classes" -name '*.class')

cp "$PROJECT_DIR/build/apk/base.apk" "$PROJECT_DIR/build/apk/unaligned.apk"
python3 - "$PROJECT_DIR/build/apk/unaligned.apk" "$PROJECT_DIR/build/apk/dex/classes.dex" <<'PY'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1], "a") as z:
    z.write(sys.argv[2], "classes.dex")
PY
"$BT/zipalign" -f 4 "$PROJECT_DIR/build/apk/unaligned.apk" "$PROJECT_DIR/build/apk/aligned.apk"

KEYSTORE="${KEYSTORE_PATH:-$PROJECT_DIR/build/debug.keystore}"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias acefitneo -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=ACEFIT NEO,O=OpenSource,C=CN"
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$PROJECT_DIR/build/acefit-neo-companion.apk" "$PROJECT_DIR/build/apk/aligned.apk"
ls -lh "$PROJECT_DIR/build/acefit-neo-companion.apk"
