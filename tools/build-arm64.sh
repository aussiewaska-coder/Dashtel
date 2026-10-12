#!/bin/sh
# Build on an arm64 host, where Google only ships x86-64 aapt2 binaries.
# Runs aapt2 under qemu-user; see tools/aapt2-qemu/aapt2 for prerequisites.
#
# Usage: tools/build-arm64.sh [gradle tasks...]   (default: assembleDebug)
set -eu
root=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root/android"
[ "$#" -gt 0 ] || set -- assembleDebug
exec ./gradlew "-Pandroid.aapt2FromMavenOverride=$root/tools/aapt2-qemu/aapt2" "$@"
