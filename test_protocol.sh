#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if command -v javac >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
  mkdir -p "$SCRIPT_DIR/build/test_classes"
  javac -source 11 -target 11 -d "$SCRIPT_DIR/build/test_classes" \
    "$SCRIPT_DIR/src/com/acefit/neo/NeoProtocol.java" \
    "$SCRIPT_DIR/test/com/acefit/neo/NeoProtocolTest.java"
  java -cp "$SCRIPT_DIR/build/test_classes" com.acefit.neo.NeoProtocolTest
else
  export MSYS_NO_PATHCONV=1
  WSL_DIR="$(wsl -d Debian wslpath -a "$SCRIPT_DIR")"
  wsl -d Debian bash -c "set -euo pipefail; mkdir -p '$WSL_DIR/build/test_classes'; javac -source 11 -target 11 -d '$WSL_DIR/build/test_classes' '$WSL_DIR/src/com/acefit/neo/NeoProtocol.java' '$WSL_DIR/test/com/acefit/neo/NeoProtocolTest.java'; java -cp '$WSL_DIR/build/test_classes' com.acefit.neo.NeoProtocolTest"
fi
