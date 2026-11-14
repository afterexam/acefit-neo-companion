#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_PATH="$SCRIPT_DIR/build/acefit-neo-companion.apk"
DEV="${1:-}"

echo "=== Running Protocol Unit Tests ==="
bash "$SCRIPT_DIR/test_protocol.sh"

echo "=== Building Companion APK ==="
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -n "$SDK_DIR" ] && [ -d "$SDK_DIR/build-tools" ]; then
  bash "$SCRIPT_DIR/build.sh"
else
  export MSYS_NO_PATHCONV=1
  WSL_DIR="$(wsl -d Debian wslpath -a "$SCRIPT_DIR")"
  wsl -d Debian bash "$WSL_DIR/build.sh"
fi

ADB_ARGS=()
if [ -n "$DEV" ]; then
  if [[ "$DEV" == *:* ]]; then
    adb connect "$DEV" >/dev/null
  fi
  ADB_ARGS=(-s "$DEV")
fi

echo "=== Installing APK ==="
adb "${ADB_ARGS[@]}" install --no-incremental -r "$APK_PATH"
for perm in android.permission.BLUETOOTH_CONNECT android.permission.BLUETOOTH_SCAN; do
  adb "${ADB_ARGS[@]}" shell "pm grant com.acefit.neo $perm" || true
done
for op in BLUETOOTH_CONNECT BLUETOOTH_SCAN; do
  adb "${ADB_ARGS[@]}" shell "cmd appops set com.acefit.neo $op allow" || true
done
adb "${ADB_ARGS[@]}" shell "am start -n com.acefit.neo/.MainActivity"
