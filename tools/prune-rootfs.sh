#!/usr/bin/env bash
# Removes everything from the rootfs that an offline single player game does not need.
# Run as: unshare -r bash tools/prune-rootfs.sh /path/to/rootfs
set -euo pipefail
R="${1:?usage: prune-rootfs.sh <rootfs>}"

rm -rf "$R/usr/lib/firmware" "$R/usr/lib/modules" "$R/usr/lib/systemd" "$R/usr/lib/udev" \
       "$R/usr/lib/security" "$R/usr/lib/xtables" "$R/usr/lib/libteflon.so" \
       "$R/usr/lib/libicudata.so"* "$R/usr/lib/pacman" \
       "$R/usr/share/vim" "$R/usr/share/gir-1.0" "$R/usr/share/i18n" "$R/usr/share/file" \
       "$R/usr/share/hwdata" "$R/usr/share/licenses" "$R/usr/share/terminfo" \
       "$R/usr/share/perl5" "$R/usr/lib/perl5" "$R/usr/share/nano" "$R/usr/share/awk" \
       "$R/usr/share/man" "$R/usr/share/doc" "$R/usr/share/info" \
       "$R/var/lib/systemd" "$R/var/log" "$R/var/cache" "$R/var/lib/pacman" \
       "$R/usr/include" "$R/usr/lib/pkgconfig" "$R/usr/share/pkgconfig" "$R/usr/share/aclocal" \
       "$R/etc/systemd" "$R/etc/udev" "$R/etc/pacman.d" \
       "$R/boot" "$R/usr/lib/gconv" "$R/usr/lib/ssh" "$R/usr/lib/ldscripts" \
       "$R/usr/lib/libnss_systemd.so"* "$R/usr/share/kbd" \
       "$R/usr/share/iana-etc" "$R/usr/share/gettext" "$R/usr/share/pacman" 2>/dev/null || true

# NOTE: libicu* must stay: libLLVM -> libxml2 -> libicuuc, and without LLVM llvmpipe
# (the software OpenGL driver) cannot be loaded at all.

# keep only a handful of userland tools (note: /usr/sbin is a symlink to bin, never clean it separately!)
KEEP="bash sh ls cat cp mv rm mkdir rmdir ln echo printf sleep ps kill grep sed gawk awk env id
whoami uname df du free sync chmod chown touch head tail wc sort uniq cut tr xargs find which date
tty stty readlink realpath dirname basename yes clear true false test expr tee mktemp nohup
glxinfo xdpyinfo xrandr xwininfo xprop xdotool xset xmodmap"
cd "$R/usr/bin"
for f in *; do
  case " $KEEP " in
    *" $f "*) ;;
    *) rm -f "$f" ;;
  esac
done

rm -f "$R/usr/lib/"*.a 2>/dev/null || true
find "$R/usr/lib" -maxdepth 1 -type d \( -name 'cmake' -o -name 'girepository*' -o -name 'gio' \) \
     -exec rm -rf {} + 2>/dev/null || true

mkdir -p "$R/tmp" "$R/dev/shm" "$R/root"
chmod 1777 "$R/tmp" "$R/dev/shm" || true

du -sh "$R"
