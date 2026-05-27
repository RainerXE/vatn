#!/usr/bin/env bash
# native/build.sh — builds both the C and Odin shared libraries
# Output: libguard_fs_c.so  (from C)
#         libguard_fs.so    (from Odin, replaces C if both built)
#
# The Java loader in NativeGuardPlugin tries libguard_fs.so first,
# then falls back to libguard_fs_c.so.

set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

echo "=== Building C version ==="
gcc -O2 -shared -fPIC \
    -o libguard_fs_c.so \
    guard_fs.c
echo "  → libguard_fs_c.so"

if command -v odin &>/dev/null; then
  echo "=== Building Odin version ==="
  odin build guard_fs.odin \
       -build-mode:shared \
       -out:libguard_fs.so
  echo "  → libguard_fs.so"
else
  echo "  (odin not found — skipping Odin build, C version will be used)"
  cp libguard_fs_c.so libguard_fs.so
fi

echo "=== Done ==="
ls -lh libguard_fs*.so
