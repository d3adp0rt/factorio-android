#!/bin/bash
# Started by the Android app through proot.
cd /opt/factorio || exit 1
unset LD_LIBRARY_PATH
export HOME=/root
export PATH=/usr/bin:/bin
export DISPLAY="${DISPLAY:-:0}"
export XDG_RUNTIME_DIR=/tmp
export LANG=C.UTF-8

# Factorio's SDL was built with ALSA+PulseAudio; there is no ALSA card inside the container,
# so use the PulseAudio server the app runs next to us (OpenSL ES sink).
if [ -f /usr/lib/libpulse.so.0 ]; then
  export SDL_AUDIODRIVER=pulseaudio
  export PULSE_SERVER="${PULSE_SERVER:-tcp:127.0.0.1:4713}"
fi

# Graphics tweaks for the software renderer only. llvmpipe cannot afford texture compression
# or streaming, but on the GPU path disabling them is actively harmful: Factorio then builds
# uncompressed atlases (4x the memory) and keeps everything resident, and the whole app blows
# through its budget (the X server process gets killed -> "XIO: fatal IO error" for the game).
# So: always drop the lines an older version of this script inserted, add them for CPU only.
if [ -f config/config.ini ]; then
  sed -i -E '/^texture-streaming=false$/d; /^texture-compression-level=none$/d; /^compress-virtual-atlas=false$/d' config/config.ini
  if [ "$BACKEND" = "cpu" ]; then
    awk 'BEGIN{done=0}
         /^\[graphics\][[:space:]]*$/ { print; if (!done) {
               print "texture-streaming=false";
               print "texture-compression-level=none";
               print "compress-virtual-atlas=false";
               done=1 } next }
         { print }' config/config.ini > config/config.ini.new \
      && mv config/config.ini.new config/config.ini
  fi
fi
if [ -n "$DIAG" ] && [ -f config/config.ini ]; then
  echo "----- diag: [graphics] section -----"
  sed -n '/^\[graphics\]/,/^\[/p' config/config.ini | grep -vE '^[[:space:]]*;' | head -40
fi

# A real gamepad device would be created through /dev/uinput; Android usually denies that to
# applications, so the answer decides whether the gamepad can be a real joystick or has to be
# translated into keys/mouse. Logged on every start, it is three lines.
echo "----- input devices -----"
ls -l /dev/uinput 2>&1 | head -2
if [ -w /dev/uinput ]; then
  echo "uinput: WRITABLE (a real virtual gamepad is possible)"
else
  echo "uinput: not writable (gamepad will be key/mouse translation)"
fi
ls /dev/input 2>/dev/null | head -4

if [ -n "$DIAG" ]; then
  export LIBGL_DEBUG=verbose
  MESA_DEBUG=1
  export MESA_DEBUG
  echo "----- diag: env -----"
  echo "DISPLAY=$DISPLAY GALLIUM_DRIVER=$GALLIUM_DRIVER LIBGL_ALWAYS_SOFTWARE=$LIBGL_ALWAYS_SOFTWARE BACKEND=$BACKEND LD_LIBRARY_PATH=$LD_LIBRARY_PATH"
  echo "----- diag: gl libs -----"
  ls -la /usr/lib/libGL.so.1 /usr/lib/libGLX*.so* /usr/lib/libEGL*.so* /usr/lib/libglapi* 2>&1 | head -20
  ls /usr/share/glvnd/egl_vendor.d 2>&1 | head -5
  echo "----- diag: extensions -----"
  xdpyinfo 2>/dev/null | sed -n '/number of extensions/,/default screen number/p' | head -30
  echo "----- diag: swrast driver deps -----"
  /usr/lib/ld-linux-aarch64.so.1 --list /usr/lib/dri/libdril_dri.so 2>&1 | grep -E "not found" | head -10
  echo "(no output above = all deps found)"
  # glxinfo is not part of the trimmed image; the game itself does not need it
  if command -v glxinfo >/dev/null 2>&1; then
    echo "----- diag: glxinfo (verbose, first 60 lines) -----"
    glxinfo 2>&1 | head -60
    echo "----- diag: glxinfo -B -----"
    glxinfo -B 2>&1 | head -10
    echo "----- diag: indirect -----"
    LIBGL_ALWAYS_INDIRECT=1 glxinfo -B 2>&1 | head -6
    echo "----- diag: swrast override -----"
    MESA_LOADER_DRIVER_OVERRIDE=swrast glxinfo -B 2>&1 | head -6
  fi
  echo "----- diag: end -----"
fi

# There is no window manager inside the container, so Factorio's own window may stay where SDL
# created it. Put it on the whole screen as soon as it shows up - but ONLY that window: an
# earlier version grabbed "any visible window" and moved/resized one of the X server's own
# windows, which broke mouse input.
if command -v xdotool >/dev/null 2>&1; then
  (
    found=""
    n=0
    while [ "$n" -lt 120 ]; do
      n=$((n + 1))
      for cand in $(xdotool search --onlyvisible --classname factorio 2>/dev/null) \
                  $(xdotool search --onlyvisible --class factorio 2>/dev/null) \
                  $(xdotool search --onlyvisible --name '^Factorio' 2>/dev/null); do
        geom=$(xdotool getwindowgeometry --shell "$cand" 2>/dev/null) || continue
        gw=$(echo "$geom" | sed -n 's/^WIDTH=//p')
        gh=$(echo "$geom" | sed -n 's/^HEIGHT=//p')
        if [ "${gw:-0}" -ge 320 ] && [ "${gh:-0}" -ge 200 ]; then
          found="$cand"
          break
        fi
      done
      [ -n "$found" ] && break
      sleep 0.5
    done
    if [ -n "$found" ]; then
      disp=$(xdotool getdisplaygeometry --shell 2>/dev/null)
      dw=$(echo "$disp" | sed -n 's/^WIDTH=//p')
      dh=$(echo "$disp" | sed -n 's/^HEIGHT=//p')
      xdotool windowmove "$found" 0 0 2>/dev/null
      xdotool windowsize "$found" "${dw:-1744}" "${dh:-853}" 2>/dev/null
      # no window manager ever gives the game the X input focus, and an unfocused window
      # gets no keyboard focus events; raise+focus makes SDL consider it active
      xdotool windowraise "$found" 2>/dev/null
      xdotool windowfocus --sync "$found" 2>/dev/null
      echo "window $found placed at 0,0 ${dw}x${dh}, focused"
    fi
  ) &
fi

./bin/arm64/factorio "$@" &
GAME=$!
wait $GAME
exit $?
