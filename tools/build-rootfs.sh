#!/usr/bin/env bash
# Builds the Arch Linux ARM (aarch64) root filesystem that is embedded into the APK
# together with the official Factorio build.
#
#   ./build-rootfs.sh /path/to/factorio   ->  $WORK/rootfs.tar
#
# Needs: pacman (Arch host), unshare (user namespaces), tar, NDK llvm-strip.
# No real root privileges are required: the script re-executes itself inside a user namespace.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(dirname "$HERE")"
WORK="${WORK:-/tmp/opencode/fp}"
ROOTFS="$WORK/rootfs"
CACHE="$WORK/pkgcache"
FACTORIO="${1:-$PROJECT/../factorio}"
STRIP="${STRIP:-$PROJECT/../.android-sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip}"
ALARM_TARBALL="${ALARM_TARBALL:-$WORK/alarm.tar.gz}"
ALARM_URL="https://mirrors.tuna.tsinghua.edu.cn/archlinuxarm/os/ArchLinuxARM-aarch64-latest.tar.gz"

if [ "$(id -u)" != "0" ]; then
  echo "== re-executing inside a user namespace (fake root)"
  exec unshare -r --map-root-user bash "$0" "$@"
fi

PACKAGES=(
  bash coreutils grep sed gawk which procps-ng util-linux
  mesa libglvnd libdrm llvm-libs
  libx11 libxext libxrandr libxrender libxcursor libxi libxfixes libxss libxkbcommon
  xkeyboard-config fontconfig freetype2
  alsa-lib
  mesa-utils xdotool xorg-xdpyinfo xorg-xrandr xorg-xwininfo xorg-xprop xorg-xset xorg-xmodmap
  libpulse xcb-util-keysyms libdisplay-info
)

echo "== workspace: $WORK"
mkdir -p "$WORK" "$CACHE" "$WORK/emptyhooks"

if [ ! -f "$ALARM_TARBALL" ]; then
  echo "== downloading Arch Linux ARM base ($ALARM_URL)"
  curl -L --retry 3 -o "$ALARM_TARBALL" "$ALARM_URL"
fi

echo "== extracting base system"
rm -rf "$ROOTFS"
mkdir -p "$ROOTFS"
tar -xzf "$ALARM_TARBALL" -C "$ROOTFS" --numeric-owner --no-same-owner --warning=no-unknown-keyword

echo "== installing packages for aarch64 (no chroot, no scriptlets)"
mkdir -p "$ROOTFS/var/lib/pacman" "$ROOTFS/dev/shm" "$ROOTFS/root"
pacman \
  --root "$ROOTFS" \
  --dbpath "$ROOTFS/var/lib/pacman" \
  --cachedir "$CACHE" \
  --arch aarch64 \
  --config "$HERE/pacman-aarch64.conf" \
  --hookdir "$WORK/emptyhooks" \
  --noscriptlet --noconfirm --needed --overwrite '*' \
  -Sy "${PACKAGES[@]}"

# The game itself is NOT part of the shipped rootfs any more: the app asks the user for a
# tar archive of their own Factorio install (see app/.../GameInstaller.java). WITH_GAME=1
# keeps the old behaviour for local testing / the "fat" image.
if [ "${WITH_GAME:-0}" = "1" ]; then
  echo "== copying Factorio ($FACTORIO)"
  mkdir -p "$ROOTFS/opt/factorio"
  tar -C "$FACTORIO" \
      --exclude=doc-html --exclude='changelog*' --exclude='data/changelog*' \
      -cf - . | tar -C "$ROOTFS/opt/factorio" -xf -

  echo "== stripping the game binary"
  if [ -x "$STRIP" ]; then
    cp "$ROOTFS/opt/factorio/bin/arm64/factorio" "$WORK/factorio.stripped"
    chmod u+w "$WORK/factorio.stripped"
    "$STRIP" --strip-all "$WORK/factorio.stripped"
    cp "$WORK/factorio.stripped" "$ROOTFS/opt/factorio/bin/arm64/factorio"
    chmod 0755 "$ROOTFS/opt/factorio/bin/arm64/factorio"
  else
    echo "   (llvm-strip not found, binary stays unstripped)"
  fi
else
  echo "== skipping Factorio (WITH_GAME=0): the user supplies the game archive"
  mkdir -p "$ROOTFS/opt/factorio"
fi

echo "== launcher script"
# The real launcher lives in the repository (rootfs/opt/factorio/run.sh) so it can be edited
# and reviewed like any other source file.
install -m 0755 "$PROJECT/rootfs/opt/factorio/run.sh" "$ROOTFS/opt/factorio/run.sh"

echo "== trimming"
rm -rf "$ROOTFS/usr/share/doc" "$ROOTFS/usr/share/man" "$ROOTFS/usr/share/info" \
       "$ROOTFS/var/cache/pacman" "$ROOTFS/usr/include" \
       "$ROOTFS/usr/lib/pkgconfig" "$ROOTFS/usr/share/pkgconfig" || true
if [ -d "$ROOTFS/usr/share/locale" ]; then
  find "$ROOTFS/usr/share/locale" -mindepth 1 -maxdepth 1 -type d \
       ! -name C ! -name 'en*' -exec rm -rf {} + || true
fi
rm -f "$ROOTFS/usr/lib/"*.a || true
mkdir -p "$ROOTFS/tmp" "$ROOTFS/dev/shm"
chmod 1777 "$ROOTFS/tmp" "$ROOTFS/dev/shm" || true

echo "== unpacked size:"
du -sh "$ROOTFS"

OUT="$WORK/rootfs.tar"
echo "== creating $OUT"
rm -f "$OUT"
tar --sort=name --numeric-owner --owner=0 --group=0 \
    -C "$ROOTFS" -cf "$OUT" .
ls -la "$OUT"
