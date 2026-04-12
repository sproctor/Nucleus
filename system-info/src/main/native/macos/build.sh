#!/bin/bash
# Compiles the macOS noop stub for nucleus_system_info.
# This is a placeholder until macOS implementation is added.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$SCRIPT_DIR/nucleus_system_info.m"
RESOURCE_DIR="$SCRIPT_DIR/../../resources/nucleus/native"
OUT_DIR_ARM64="$RESOURCE_DIR/darwin-aarch64"
OUT_DIR_X64="$RESOURCE_DIR/darwin-x64"

if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || true)
fi
if [ -z "${JAVA_HOME:-}" ]; then
    echo "ERROR: JAVA_HOME not set." >&2
    exit 1
fi

JNI_INCLUDE="$JAVA_HOME/include"
JNI_INCLUDE_DARWIN="$JAVA_HOME/include/darwin"

mkdir -p "$OUT_DIR_ARM64" "$OUT_DIR_X64"

clang -arch arm64 -shared -fPIC \
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_DARWIN" \
    -O2 -fvisibility=hidden \
    -framework Foundation \
    -o "$OUT_DIR_ARM64/libnucleus_system_info.dylib" "$SRC"

clang -arch x86_64 -shared -fPIC \
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_DARWIN" \
    -O2 -fvisibility=hidden \
    -framework Foundation \
    -o "$OUT_DIR_X64/libnucleus_system_info.dylib" "$SRC"

echo "Built macOS stubs:"
ls -lh "$OUT_DIR_ARM64/libnucleus_system_info.dylib"
ls -lh "$OUT_DIR_X64/libnucleus_system_info.dylib"
