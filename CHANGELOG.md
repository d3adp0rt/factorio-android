# Changelog

## 0.4.0

**The game is no longer shipped with the app.**

* The APK contains only the Linux environment (Arch Linux ARM + Mesa + X libraries).
  On first launch the launcher asks for a tar archive of the user's own Factorio build
  (`.tar`, `.tar.gz`, `.tar.xz`, `.tar.zst`, sniffed by magic bytes) and offers to delete the
  archive afterwards.
* Space Age / base-game badge in the launcher (detected from the installed data).
* Saves and mods can be imported from the phone's storage.
* Settings split into sections with a circled ⓘ next to every option that explains it.
* Interface language switch (system / Russian / English).
* Layout editor: drag buttons, add, delete, change the action, save.
* Gamepad preset: analogue stick + controller-style buttons, plus forwarding of a real
  controller paired with the phone.
* The game window is placed on the whole screen with `xdotool` (there is no window manager
  inside the container, so it used to stay in the corner).
* The small "overlay" patch archive is gone: `run.sh` and the extra libraries now live in the
  base image, so there is one less moving part and 15 MB less in the APK.

## 0.3.1

* Fixed "no connection to the X server" while loading sprites: the software-renderer tuning
  (`texture-compression-level=none`, `texture-streaming=false`) was applied on the GPU path
  too, which quadrupled atlas memory and got the X server process killed. It is CPU-only now.

## 0.3.0

* Zink (Venus) and Holy GL4ES removed from the menu: Mesa 26 requires
  `VK_EXT_robustness2::nullDescriptor` for zink and GL4ES cannot compile Factorio's GL 3.3
  shaders.
* On-screen controls: touch preset, multi-touch split dispatch (walk with the left thumb,
  aim with the right one), collapsible panel.

## 0.2.4

* Emulated `PIPE_QUERY_TIMESTAMP`: Mali has no timer queries and Factorio asks for one every
  frame; rejecting them eventually killed the context.

## 0.2.3

* Stopped the tolerant command dispatcher from poisoning the context
  (`vrend_report_buffer_error` set `in_error`, after which every draw returned
  `ENOTRECOVERABLE`).

## 0.2.2

* `virglrenderer` built from source for Android: EGL without GBM, host GL errors demoted to
  warnings, tolerant command dispatch, `--use-gles` (Android only has GLES through EGL).
* Venus runs with an in-process render server.

## 0.2.1

* Fixed a missing `__android_log_print` (the render server could not even be loaded) and a
  missing `vgpu/shaderconv.c` in the GL4ES build.

## 0.2.0

* Four selectable renderers (CPU, VirGL, Zink, GL4ES) instead of the automatic fallback chain.

## 0.1.x

* First working build: bundled rootfs, proot, Lorie X server, software rendering, sound,
  save persistence.
