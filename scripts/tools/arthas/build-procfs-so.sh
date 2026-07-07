#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BRIDGE_DIR="$SCRIPT_DIR/../arthas-bridge/src/main/jni"

if [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -z "${ANDROID_HOME:-}" ]; then
    if [ -f "$SCRIPT_DIR/../../local.properties" ]; then
        SDK_DIR=$(grep 'sdk.dir' "$SCRIPT_DIR/../../local.properties" | cut -d= -f2)
        export ANDROID_SDK_ROOT="$SDK_DIR"
    fi
fi

if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    echo "Error: ANDROID_SDK_ROOT not set and could not be detected." >&2
    exit 1
fi

NDK_DIR=$(ls -d "$ANDROID_SDK_ROOT/ndk/"* 2>/dev/null | sort -V | tail -1)
if [ -z "$NDK_DIR" ]; then
    echo "Error: No NDK found under $ANDROID_SDK_ROOT/ndk/" >&2
    exit 1
fi

TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
CLANG="$TOOLCHAIN/aarch64-linux-android26-clang"

if [ ! -f "$CLANG" ]; then
    echo "Error: clang not found at $CLANG" >&2
    exit 1
fi

set -x
"$CLANG" -shared -o "$BRIDGE_DIR/libprocfs_cpu.so" "$BRIDGE_DIR/procfs_cpu.c" \
    -Wall -Wextra -O2 -s

echo "Built: $BRIDGE_DIR/libprocfs_cpu.so"
