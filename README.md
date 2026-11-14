# Factorio for Android

Run the official **aarch64 Linux build of Factorio** on an Android phone, with hardware
acceleration through the phone's GPU.

The app does **not** contain the game. Factorio is proprietary, so you have to own a copy:
the launcher asks for a tar archive of your own Linux installation and unpacks it into the
bundled Linux environment.

```
┌───────────────────────────── Android app ─────────────────────────────┐
│  LauncherActivity  ──  XServerService (:x11)  ──  Lorie X server       │
│         │                      │                     │                │
│         │                 virgl_test_server ──► EGL/GLES ──► Mali GPU │
│         │                      │                                      │
│         └──► proot ──► Arch Linux ARM rootfs ──► Factorio (x11/GLX)   │
└───────────────────────────────────────────────────────────────────────┘
```

## What works

* Factorio 2.x reaches the main menu and runs at a playable frame rate on a Mali-G925.
* Sound through PulseAudio → OpenSL ES.
* On-screen gamepad (touch preset and gamepad preset) with a drag & drop layout editor.
* A real controller paired with the phone is forwarded into the game.
* Saves and mods can be imported from the phone's storage.
* Software rendering (llvmpipe) as a fallback.

## Requirements

* Android 9+ (API 28), `arm64-v8a`.
* ~4 GB of free space (Linux environment + your game archive).
* A Factorio Linux build for **arm64** (the official `factorio_linux_*.tar.xz` from
  factorio.com or a GOG/Steam copy of the Linux build).

## Install

1. Install the APK.
2. Tap **Install** — the bundled Arch Linux ARM environment is unpacked (~30 s).
3. Tap **Install the game** and pick your Factorio archive (`.tar`, `.tar.gz`, `.tar.xz`,
   `.tar.zst`). The app offers to delete the archive afterwards.
4. Tap **Play**.

`targetSdkVersion` is deliberately 28: Android 10+ forbids executing binaries from the app's
data directory for `untrusted_app_29+`, and both the X server and `proot` are started that way.

## Building

Everything is reproducible from source; nothing proprietary is needed to build the app itself.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=$PWD/../.android-sdk

# 1. Termux helper binaries (proot, pulseaudio, a virgl build) - downloaded, not committed
python3 tools/gettermux.py proot virglrenderer-android pulseaudio

# 2. virglrenderer + libepoxy built for Android with the NDK (meson)
bash tools/build-virgl.sh
VIRGL_BUILD=/tmp/opencode/fp/vr-build EPOXY_BUILD=/tmp/opencode/fp/andeps \
  PATCHELF=/tmp/opencode/fp/pvenv/bin/patchelf bash tools/stage-assets.sh

# 3. The Linux environment (Arch Linux ARM + Mesa + X libraries, no game)
WITH_GAME=0 bash tools/build-rootfs.sh /path/to/factorio
unshare -r bash tools/prune-rootfs.sh ../.work/rootfs
zstd -T0 -12 ../.work/rootfs.tar -o app/src/main/assets/rootfs.tar.zst

# 4. The APK
./gradlew --no-daemon --no-configuration-cache assembleRelease
```

CI does exactly this: see `.github/workflows/build.yml`.

## Layout

| Path | What |
|---|---|
| `app/` | the launcher, installer, settings, layout editor |
| `lorie/` | fork of termux-x11 (GPLv3), the X server and its input handling |
| `stub/` | stubs for hidden Android APIs used by the X server entry point |
| `rootfs/opt/factorio/run.sh` | the script that starts the game inside the container |
| `tools/build-rootfs.sh` | builds the Arch Linux ARM image (no game) |
| `tools/build-virgl.sh` | builds libepoxy + virglrenderer with the NDK |
| `tools/stage-assets.sh` | stages proot/pulseaudio/virgl into the APK assets |
| `tools/prune-rootfs.sh` | strips docs, firmwares and unused binaries |

## Notes on the GPU path

`virglrenderer` is built with three deliberate deviations from upstream, all documented in
`tools/build-virgl.sh`:

1. EGL without GBM/libdrm (Android has no `/dev/dri` for us).
2. `-Dcheck-gl-errors=false` — upstream turns any host GL error into a fatal command dispatch
   error, and Mali reports `GL_INVALID_VALUE` on some texture uploads.
3. A tolerant command dispatcher: an unsupported command (Mali has no timer queries) is skipped
   instead of tearing down the context, and `PIPE_QUERY_TIMESTAMP` is emulated as "0 ns".

## Licence

GPLv3 — see [LICENSE](LICENSE). `lorie/` is a fork of [termux-x11](https://github.com/termux/termux-x11)
(GPLv3). Factorio is a trademark of Wube Software; this project ships none of its files.
