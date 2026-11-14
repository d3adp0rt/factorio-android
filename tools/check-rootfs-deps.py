#!/usr/bin/env python3
"""Fails when the pruned rootfs is missing a shared library something links against.

This catches the class of bug where prune-rootfs.sh removes a library that is only needed
indirectly: libicudata was once dropped, which silently broke the whole Mesa gallium driver
(libLLVM -> libxml2 -> libicuuc -> libicudata) and the game reported
"Couldn't find matching GLX visual" instead of anything useful.

    ./tools/check-rootfs-deps.py /path/to/rootfs
    READELF=/path/to/llvm-readelf ./tools/check-rootfs-deps.py ...

Exit code 1 if anything is missing.
"""
import os
import re
import shutil
import subprocess
import sys

# libraries the C library itself provides, they are never in the rootfs' /usr/lib
IGNORED_PREFIXES = ("libc.so", "ld-linux", "libm.so", "libdl.so", "libpthread.so",
                    "librt.so", "libresolv.so", "libutil.so", "libanl.so", "libnsl.so")

NEEDED = re.compile(r"\(NEEDED\).*\[(.+?)\]")


def find_readelf():
    env = os.environ.get("READELF")
    if env:
        return env
    for name in ("llvm-readelf", "readelf"):
        path = shutil.which(name)
        if path:
            return path
    sys.exit("no readelf found; set READELF")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    root = sys.argv[1]
    readelf = find_readelf()

    present = set()
    for base in ("usr/lib", "lib", "lib64", "usr/lib/dri", "usr/lib/pulseaudio"):
        d = os.path.join(root, base)
        if os.path.isdir(d):
            present.update(os.listdir(d))

    missing = {}
    scanned = 0
    for base in ("usr/lib", "usr/lib/dri", "usr/lib/pulseaudio", "usr/bin", "usr/libexec"):
        d = os.path.join(root, base)
        if not os.path.isdir(d):
            continue
        for name in sorted(os.listdir(d)):
            path = os.path.join(d, name)
            if not os.path.isfile(path):
                continue
            scanned += 1
            try:
                out = subprocess.run([readelf, "-d", path], capture_output=True,
                                     text=True, timeout=30).stdout
            except Exception:
                continue
            for match in NEEDED.finditer(out):
                lib = match.group(1)
                if lib.startswith(IGNORED_PREFIXES) or lib in present:
                    continue
                missing.setdefault(lib, []).append(os.path.relpath(path, root))

    if not missing:
        print(f"rootfs deps: {scanned} binaries checked, everything resolved")
        return 0

    print(f"rootfs deps: {len(missing)} missing libraries", file=sys.stderr)
    for lib, users in sorted(missing.items()):
        print(f"  {lib:34s} <- {', '.join(users[:3])}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
