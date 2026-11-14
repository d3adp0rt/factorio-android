#!/usr/bin/env bash
# Builds virglrenderer (and its only missing dependency, libepoxy) for Android/arm64
# with the NDK and meson. The result is consumed by tools/stage-assets.sh.
#
#   ./tools/build-virgl.sh            # clones into $WORK (default /tmp/opencode/fp) and builds
#   VIRGL_BUILD=... EPOXY_BUILD=... tools/stage-assets.sh
#
# Deviations from an upstream build, all required on Android:
#   * platforms=egl  but no GBM/libdrm (Android has no /dev/dri for us) - meson patch
#     plus a small vrend_winsys.c branch that runs EGL on the default display.
#   * check-gl-errors=off. Upstream treats any host GL error as a fatal command
#     dispatch error; Mali reports GL_INVALID_VALUE on some texture uploads and that
#     used to kill the whole context, which crashed Factorio.
#   * venus=on with render-server-mode=thread, so `--venus` does not need to spawn
#     a separate virgl_render_server binary (it cannot exec another one easily).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(dirname "$HERE")"
WORK="${WORK:-/tmp/opencode/fp}"
NDK="${ANDROID_NDK_ROOT:-$PROJECT/../.android-sdk/ndk/29.0.14206865}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API="${API:-30}"
PY="${PY:-$WORK/pvenv/bin}"

mkdir -p "$WORK"
export PATH="$PY:$PATH"

# ---------------------------------------------------------------- helpers
command -v meson >/dev/null || { echo "meson not found (pip install meson ninja)"; exit 1; }
python3 -c "import mako, yaml" 2>/dev/null || pip install -q mako pyyaml

# pkg-config files for the NDK's EGL/GLES (the NDK does not ship any)
PC="$WORK/pc-android"
mkdir -p "$PC"
SYS="$TC/sysroot"
LIB="$SYS/usr/lib/aarch64-linux-android/$API"
for n in egl:EGL:1.5 glesv2:GLESv2:2.0; do
  IFS=: read -r file lib ver <<<"$n"
  cat > "$PC/$file.pc" <<EOF
prefix=$SYS/usr
libdir=$LIB
includedir=\${prefix}/include

Name: $lib
Description: Android $lib
Version: $ver
Libs: -L\${libdir} -l$lib
Cflags: -I\${includedir}
EOF
done

# mesa's os_misc.c includes the AOSP-internal log/log.h and cutils/properties.h.
# log/log.h must NOT expand to __android_log_print: liblog is not linked, and an undefined
# symbol in a shared library is only noticed at dlopen() time - that killed the render server
# with 'cannot locate symbol "__android_log_print"'. fprintf(stderr) is enough, the app
# captures the server's stderr.
COMPAT="$WORK/compat-android"
mkdir -p "$COMPAT/log" "$COMPAT/cutils"
cat > "$COMPAT/log/log.h" <<'EOF'
#ifndef VIRGL_COMPAT_LOG_LOG_H
#define VIRGL_COMPAT_LOG_LOG_H
#include <stdio.h>
#ifndef LOG_TAG
#define LOG_TAG "virgl"
#endif
#define LOG_PRI(priority, tag, ...) do { fputs(tag ": ", stderr); fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOG_PRI_VA(priority, tag, fmt, ap) do { fputs(tag ": ", stderr); vfprintf(stderr, fmt, ap); fputc('\n', stderr); } while (0)
#endif
EOF
cat > "$COMPAT/cutils/properties.h" <<'EOF'
#ifndef VIRGL_COMPAT_CUTILS_PROPERTIES_H
#define VIRGL_COMPAT_CUTILS_PROPERTIES_H
#include <string.h>
#include <sys/system_properties.h>
#define PROPERTY_VALUE_MAX PROP_VALUE_MAX
#define PROPERTY_KEY_MAX PROP_NAME_MAX
static inline int property_get(const char *key, char *value, const char *def)
{
   int len = __system_property_get(key, value);
   if (len > 0)
      return len;
   if (def) {
      strncpy(value, def, PROP_VALUE_MAX - 1);
      value[PROP_VALUE_MAX - 1] = 0;
      return (int)strlen(value);
   }
   value[0] = 0;
   return 0;
}
#endif
EOF

CROSS="$WORK/android-aarch64.txt"
cat > "$CROSS" <<EOF
[binaries]
c = '$TC/bin/aarch64-linux-android$API-clang'
cpp = '$TC/bin/aarch64-linux-android$API-clang++'
ar = '$TC/bin/llvm-ar'
strip = '$TC/bin/llvm-strip'
ranlib = '$TC/bin/llvm-ranlib'
nm = '$TC/bin/llvm-nm'
objcopy = '$TC/bin/llvm-objcopy'
pkg-config = '/usr/bin/pkg-config'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[properties]
needs_exe_wrapper = true

[built-in options]
c_std = 'gnu11'
# fail at build time when a symbol cannot be resolved instead of at dlopen() time
b_lundef = true
c_args = ['-O2', '-fPIC', '-I$COMPAT']
cpp_args = ['-O2', '-fPIC', '-I$COMPAT']
EOF

export PKG_CONFIG_LIBDIR="$PC:$WORK/andeps/lib/pkgconfig"
export PKG_CONFIG_PATH="$PKG_CONFIG_LIBDIR"

# ---------------------------------------------------------------- libepoxy
if [ ! -d "$WORK/epoxy" ]; then
  GIT_TERMINAL_PROMPT=0 git clone --depth 1 https://github.com/anholt/libepoxy.git "$WORK/epoxy"
fi
meson setup --wipe "$WORK/epoxy-build" "$WORK/epoxy" --cross-file "$CROSS" \
  --prefix="$WORK/andeps" -Degl=yes -Dglx=no -Dx11=false -Dtests=false -Ddocs=false >/dev/null
ninja -C "$WORK/epoxy-build" install

# ---------------------------------------------------------------- virglrenderer
if [ ! -d "$WORK/vr" ]; then
  GIT_TERMINAL_PROMPT=0 git clone --depth 1 \
    https://gitlab.freedesktop.org/virgl/virglrenderer.git "$WORK/vr"
fi

# patch 1: EGL without GBM on android
python3 - "$WORK/vr/meson.build" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
old = "      if with_host_windows or with_host_darwin\n         have_egl = true"
new = ("      if with_host_windows or with_host_darwin or host_machine.system() == 'android'\n"
       "         # android has no GBM/libdrm; EGL is used directly on the default display\n"
       "         have_egl = true")
if 'host_machine.system() == \'android\'' not in s:
    assert old in s, "meson.build layout changed"
    open(p, 'w').write(s.replace(old, new))
PY

# patch 2: use EGL even when the build has no GBM support
python3 - "$WORK/vr/src/vrend/vrend_winsys.c" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
old = """      use_context = CONTEXT_EGL;
#else
      (void)preferred_fd;
      virgl_error("EGL is not supported on this platform\\n");
      return -1;
#endif"""
new = """      use_context = CONTEXT_EGL;
#elif defined(HAVE_EPOXY_EGL_H)
      /* Android: no GBM, use EGL directly on the default display. */
      (void)preferred_fd;
      egl = virgl_egl_init(EGL_DEFAULT_DISPLAY,
                           flags & VIRGL_RENDERER_USE_SURFACELESS,
                           flags & VIRGL_RENDERER_USE_GLES);
      if (!egl)
         return -1;

      use_context = CONTEXT_EGL;
#else
      (void)preferred_fd;
      virgl_error("EGL is not supported on this platform\\n");
      return -1;
#endif"""
if 'Android: no GBM, use EGL' not in s:
    assert old in s, "vrend_winsys.c layout changed"
    open(p, 'w').write(s.replace(old, new))
PY

# patch 3+4: a single command the host GL stack cannot handle must not kill the context,
# and we want to know *which* query type got rejected
python3 - "$WORK/vr/src/vrend" <<'PY'
import sys

base = sys.argv[1] + '/'

p = base + 'vrend_decode.c'
s = open(p).read()
if 'tolerated_errors' not in s:
    old = "struct vrend_decode_ctx {\n   struct virgl_context base;\n   struct vrend_context *grctx;\n};"
    new = ("struct vrend_decode_ctx {\n   struct virgl_context base;\n   struct vrend_context *grctx;\n"
           "   /* commands the host GL stack could not handle but that were skipped instead of\n"
           "    * tearing down the whole context, see vrend_decode_ctx_submit_cmd() */\n"
           "   uint32_t tolerated_errors;\n};")
    assert old in s, 'vrend_decode_ctx layout changed'
    s = s.replace(old, new, 1)

    old = """         if (ret == EINVAL)
            vrend_report_buffer_error(gdctx->grctx, *buf);
         return ret;"""
    new = """         if (ret == EINVAL) {
            /* Tolerant mode: one command the host GL stack cannot handle (e.g. a timestamp
             * query, Mali has no EXT_disjoint_timer_query) must not kill the context and
             * with it the game - the guest just ends up with a dangling handle.
             *
             * Do NOT call vrend_report_buffer_error() here: it sets ctx->in_error, and then
             * vrend_draw_vbo() returns ENOTRECOVERABLE (131) for every subsequent frame,
             * which kills the client anyway. Give up on a real desync though, otherwise the
             * log is flooded forever. */
            vrend_context_clear_error(gdctx->grctx);
            if (++gdctx->tolerated_errors > 4096)
               return ret;
            continue;
         }
         return ret;"""
    assert old in s, 'submit_cmd layout changed'
    s = s.replace(old, new, 1)
    open(p, 'w').write(s)
    print('patched', p)

# vrend_context_clear_error(): ctx->in_error lives in vrend_renderer.c, so the decoder needs
# a way to drop the poison flag after it skipped a command
p = base + 'vrend_renderer.c'
s = open(p).read()
if 'vrend_context_clear_error' not in s:
    old = "#define CORE_PROFILE_WARN_NONE 0"
    new = ("void vrend_context_clear_error(struct vrend_context *ctx)\n"
           "{\n"
           "   ctx->in_error = false;\n"
           "}\n\n"
           "#define CORE_PROFILE_WARN_NONE 0")
    assert old in s, 'vrend_renderer.c layout changed (clear_error)'
    s = s.replace(old, new, 1)
    open(p, 'w').write(s)
    print('patched', p)

p = base + 'vrend_renderer.h'
s = open(p).read()
if 'vrend_context_clear_error' not in s:
    old = """#define vrend_report_buffer_error(ctx, cmd) \\
    vrend_report_context_error(ctx, VIRGL_ERROR_CTX_ILLEGAL_CMD_BUFFER, cmd)
"""
    new = old + """
/* drops the "this context is broken" flag set by vrend_report_context_error, used by the
 * tolerant command dispatcher so that one unsupported command does not poison every later
 * draw (vrend_draw_vbo returns ENOTRECOVERABLE once ctx->in_error is set) */
void vrend_context_clear_error(struct vrend_context *ctx);
"""
    assert old in s, 'vrend_renderer.h layout changed'
    s = s.replace(old, new, 1)
    open(p, 'w').write(s)
    print('patched', p)

p = base + 'vrend_renderer.c'
s = open(p).read()
if 'query create rejected' not in s:
    old = """   if (err) {
      vrend_resource_reference(&q->res, NULL);
      FREE(q);
   }

   return err;"""
    new = """   if (err) {
      virgl_warn("query create rejected: type %d index %d (host is missing the extension)\\n",
                 q->type, q->index);
      vrend_resource_reference(&q->res, NULL);
      FREE(q);
   }

   return err;"""
    assert old in s, 'vrend_create_query layout changed'
    s = s.replace(old, new, 1)
    open(p, 'w').write(s)
    print('patched', p)

# Mali GLES has no timer queries, and Factorio asks for GL_TIMESTAMP constantly (GPU
# timings). Rejecting them made every frame fail, so emulate them as "done, 0".
p = base + 'vrend_renderer.c'
s = open(p).read()
if 'bool fake;' not in s:
    old = """   struct vrend_resource *res;
   bool fake_samples_passed;
};"""
    new = """   struct vrend_resource *res;
   bool fake_samples_passed;
   /* the host has no timer queries (GLES without EXT_disjoint_timer_query), so the
    * result is emulated as 0 instead of rejecting the query (see vrend_create_query) */
   bool fake;
};"""
    assert old in s, 'vrend_query layout changed'
    s = s.replace(old, new, 1)

    old = """   case PIPE_QUERY_TIMESTAMP:
      if (has_feature(feat_timer_query))
         q->gltype = GL_TIMESTAMP;
      else
         err = EINVAL;
      break;
   case PIPE_QUERY_TIME_ELAPSED:
      if (has_feature(feat_timer_query))
         q->gltype = GL_TIME_ELAPSED;
      else
         err = EINVAL;
      break;"""
    new = """   case PIPE_QUERY_TIMESTAMP:
      if (has_feature(feat_timer_query))
         q->gltype = GL_TIMESTAMP;
      else
         q->fake = true;
      break;
   case PIPE_QUERY_TIME_ELAPSED:
      if (has_feature(feat_timer_query))
         q->gltype = GL_TIME_ELAPSED;
      else
         q->fake = true;
      break;
   case PIPE_QUERY_TIMESTAMP_DISJOINT:
      if (has_feature(feat_timer_query))
         q->gltype = GL_TIME_ELAPSED;
      else
         q->fake = true;
      break;"""
    assert old in s, 'query switch layout changed'
    s = s.replace(old, new, 1)

    old = """   state.result_size = vrend_is_timer_query(query->gltype) ? 8 : 4;
   ret = vrend_get_one_query_result(query->id, state.result_size == 8,
         &state.result);
   if (ret == false)
      return false;"""
    new = """   if (query->fake) {
      /* no timer query on the host: report "done, 0 ns" */
      memset(&state, 0, sizeof(state));
      state.result = 0;
      ret = true;
   } else {
      state.result_size = vrend_is_timer_query(query->gltype) ? 8 : 4;
      ret = vrend_get_one_query_result(query->id, state.result_size == 8,
            &state.result);
      if (ret == false)
         return false;
   }"""
    assert old in s, 'vrend_check_query layout changed'
    s = s.replace(old, new, 1)

    old = """   if (q->index > 0 && !has_feature(feat_transform_feedback3))
      return EINVAL;

   list_delinit(&q->waiting_queries);"""
    new = """   if (q->index > 0 && !has_feature(feat_transform_feedback3))
      return EINVAL;

   if (q->fake)
      return 0;

   list_delinit(&q->waiting_queries);"""
    assert old in s, 'vrend_begin_query layout changed'
    s = s.replace(old, new, 1)

    old = """   if (q->index > 0 && !has_feature(feat_transform_feedback3))
      return EINVAL;

   if (vrend_is_timer_query(q->gltype)) {"""
    new = """   if (q->index > 0 && !has_feature(feat_transform_feedback3))
      return EINVAL;

   if (q->fake)
      return 0;

   if (vrend_is_timer_query(q->gltype)) {"""
    assert old in s, 'vrend_end_query layout changed'
    s = s.replace(old, new, 1)
    open(p, 'w').write(s)
    print('patched', p)
PY

meson setup --wipe "$WORK/vr-build" "$WORK/vr" --cross-file "$CROSS" --prefix="$WORK/vrout" \
  -Dplatforms=egl -Dvenus=true -Dcheck-gl-errors=false \
  -Drender-server-mode=thread -Drender-server-worker=thread \
  -Dtests=false -Dvideo=false -Dunstable-apis=false -Dbuildtype=release
ninja -C "$WORK/vr-build"

echo
echo "built:"
echo "  $WORK/vr-build/vtest/virgl_test_server"
echo "  $WORK/vr-build/src/libvirglrenderer.so"
echo "  $WORK/andeps/lib/libepoxy.so"
echo
echo "now run: VIRGL_BUILD=$WORK/vr-build EPOXY_BUILD=$WORK/andeps tools/stage-assets.sh"
