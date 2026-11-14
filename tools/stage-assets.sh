#!/usr/bin/env bash
# Stages the Android-side helper binaries (proot, VirGL render server, PulseAudio)
# into app/src/main/assets. They are normal Android (bionic) ELF files taken from Termux
# packages, see tools/gettermux.py.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(dirname "$HERE")"
TR="${TR:-$HERE/termux-root}"
A="$PROJECT/app/src/main/assets"

[ -d "$TR" ] || { echo "missing $TR (run: python3 tools/gettermux.py proot virglrenderer-android pulseaudio)"; exit 1; }

mkdir -p "$A"
# keep the (huge) rootfs archive, only replace the helper files
rm -rf "$A/bin" "$A/proot" "$A/pulse" "$A/virgl" "$A/angle" "$A/pack"
mkdir -p "$A/bin" "$A/proot/libexec" "$A/pulse/modules" "$A/virgl" "$A/angle"

# the launcher script lives in the repository and is written into the game directory by the
# app, so it survives re-installing the game (the whole /opt/factorio is replaced then)
cp -a "$PROJECT/rootfs/opt/factorio/run.sh" "$A/run.sh"

echo "== bin"
cp -a "$TR/bin/proot" "$TR/bin/pulseaudio" "$A/bin/"
if [ -z "${VIRGL_BUILD:-}" ]; then
  cp -a "$TR/bin/virgl_test_server_android" "$A/bin/"
fi

echo "== proot runtime"
cp -aL "$TR/lib/libtalloc.so.2" "$TR/lib/libandroid-shmem.so" "$A/proot/"
cp -a "$TR/libexec/proot/loader" "$A/proot/libexec/"

echo "== pulseaudio runtime"
find "$TR/lib" -maxdepth 1 \( -type f -o -type l \) -name '*.so*' -exec cp -aL {} "$A/pulse/" \;
cp -aL "$TR/lib/pulseaudio/modules/"*.so "$A/pulse/modules/"

if [ -n "${VIRGL_BUILD:-}" ]; then
  # virglrenderer built locally for Android with the NDK (see tools/build-virgl.sh).
  # Variant: EGL without GBM/drm, venus with the in-process render server and
  # host GL errors downgraded to warnings (they used to kill the context).
  echo "== virgl (local NDK build: $VIRGL_BUILD)"
  cp -a "$VIRGL_BUILD/vtest/virgl_test_server" "$A/bin/virgl_test_server"
  cp -a "$VIRGL_BUILD/src/libvirglrenderer.so" "$A/virgl/"
  cp -a "${EPOXY_BUILD:-/tmp/opencode/fp/andeps}/lib/libepoxy.so" "$A/virgl/"
else
  echo "== virgl + ANGLE"
  cp -aL "$TR/opt/virglrenderer-android/lib/"*.so "$A/virgl/"
  # ANGLE keeps its own names: virglrenderer dlopen()s "libEGL_angle.so" explicitly for
  # --angle-vulkan. NEVER create libEGL.so/libGLESv2.so aliases here - that shadows the
  # system libraries and breaks libandroid_runtime.so in the same process.
  cp -aL "$TR/opt/angle-android/vulkan/libEGL_angle.so" "$A/angle/"
  cp -aL "$TR/opt/angle-android/vulkan/libGLESv2_angle.so" "$A/angle/"
  cp -aL "$TR/opt/angle-android/vulkan/libGLESv1_CM_angle.so" "$A/angle/"

  # virglrenderer has the Termux ANGLE path compiled in; rewrite it to our short path
  echo "== patching ANGLE path in the virgl server"
  python3 - "$A/bin/virgl_test_server_android" "/data/data/io.github.factorioandroid/files/av" <<'PY'
import re, sys
path, new = sys.argv[1], sys.argv[2].encode()
data = bytearray(open(path, 'rb').read())
n = 0
for m in list(re.finditer(rb'/data/data/com\.termux/files/usr/opt/angle-android/[a-z-]+', data)):
    old = m.group()
    if len(new) <= len(old):
        data[m.start():m.start() + len(old)] = new + b'\x00' * (len(old) - len(new))
        n += 1
open(path, 'wb').write(data)
print("  patched", n, "angle path(s)")
PY
  cp -a "$TR/lib/libc++_shared.so" "$A/virgl/" 2>/dev/null || true
fi

echo "== fixing RUNPATH (Android's linker ignores LD_LIBRARY_PATH for app processes)"
PATCHELF="${PATCHELF:-patchelf}"
BASE="/data/user/0/io.github.factorioandroid/files/android"
if command -v "$PATCHELF" >/dev/null; then
  patch_rpath() { "$PATCHELF" --set-rpath "$2" "$1"; }
  patch_rpath "$A/bin/proot"                    "$BASE/proot"
  patch_rpath "$A/bin/pulseaudio"               "$BASE/pulse"
  for f in "$A"/bin/virgl_test_server "$A"/bin/virgl_test_server_android; do
    if [ -f "$f" ]; then patch_rpath "$f" "$BASE/virgl"; fi
  done
  for f in "$A"/proot/*.so*; do patch_rpath "$f" "$BASE/proot"; done
  for f in "$A"/pulse/*.so*; do patch_rpath "$f" "$BASE/pulse"; done
  for f in "$A"/pulse/modules/*.so; do patch_rpath "$f" "$BASE/pulse"; done
  for f in "$A"/virgl/*.so*; do patch_rpath "$f" "$BASE/virgl"; done
else
  echo "!! patchelf not found: the helpers will not find their libraries on the device"
fi

du -sh "$A" "$A"/*
